package com.martinfou.trading.strategies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Story 50.1 L1.4 — un ordre qui peut ouvrir un trade porte {@code tradeClientExtensions}, un ordre
 * reduce-only n'en porte pas.
 *
 * <p>La distinction n'est pas cosmétique : {@code tradeClientExtensions} est ce que
 * {@code GET /openTrades} renvoie pour le trade, donc la seule forme qui rend une position
 * attribuable à une stratégie après coup. Mais OANDA refuse ce champ sur un ordre
 * {@code positionFill: REDUCE_ONLY}, et les fermetures de position passent exactement par là
 * ({@code LiveStrategyRunner:1516} et {@code :1897}). L'envoyer partout ferait échouer des closes.
 */
class TradeClientExtensionsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OandaExecutor executor = new OandaExecutor("fake-key", "123", true);

    @Test
    void marketEntryCarriesTradeExtensions() throws Exception {
        String body = executor.buildMarketOrderBody("USD_CHF", "4600", "vwpreversion_USDCHF",
            false, "0.82832", "0.82482");
        JsonNode order = mapper.readTree(body).get("order");

        assertEquals("vwpreversion_USDCHF", order.get("clientExtensions").get("tag").asText());
        assertTrue(order.has("tradeClientExtensions"), "an entry must tag the trade, not just the order");
        assertEquals("vwpreversion_USDCHF", order.get("tradeClientExtensions").get("tag").asText());
    }

    @Test
    void marketReduceOnlyDoesNotCarryTradeExtensions() throws Exception {
        String body = executor.buildMarketOrderBody("USD_CHF", "-4600", "vwpreversion_USDCHF",
            true, null, null);
        JsonNode order = mapper.readTree(body).get("order");

        assertEquals("REDUCE_ONLY", order.get("positionFill").asText());
        assertEquals("vwpreversion_USDCHF", order.get("clientExtensions").get("tag").asText(),
            "the order itself stays attributable");
        assertFalse(order.has("tradeClientExtensions"),
            "OANDA rejects tradeClientExtensions on a reduce-only order: a close must never send it");
    }

    @Test
    void stopEntryCarriesTradeExtensionsAndStopReduceOnlyDoesNot() throws Exception {
        JsonNode entry = mapper.readTree(executor.buildStopOrderBody("GBP_JPY", "3600", "215.500",
            "consecbar_GBPJPY", false, null, null)).get("order");
        assertTrue(entry.has("tradeClientExtensions"));

        JsonNode close = mapper.readTree(executor.buildStopOrderBody("GBP_JPY", "-3600", "215.500",
            "consecbar_GBPJPY", true, null, null)).get("order");
        assertFalse(close.has("tradeClientExtensions"));
    }
}
