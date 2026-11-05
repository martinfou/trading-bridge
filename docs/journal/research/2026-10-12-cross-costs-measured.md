# Cout reel des crosses refuge — mesure OANDA practice (12 oct 2026)

Procedure : mediane bid/ask sur 1000 bougies H1 `price=BA` (FEE-AUDIT §2).

| Symbole | spread med (pip) | half/leg (prix) | half/leg (bp) | A/R (bp) | longRate | shortRate |
|---|---|---|---|---|---|---|
| GBP_CHF | 2.50 | 0.000125 | 1.139 | 2.278 | 0.0284 | -0.0501 |
| EUR_CHF | 1.60 | 0.000080 | 0.851 | 1.702 | 0.0166 | -0.0378 |
| AUD_CHF | 1.50 | 0.000075 | 1.292 | 2.584 | 0.0365 | -0.0584 |
| EUR_USD | 1.60 | 0.000080 | 0.692 | 1.384 | -0.0244 | 0.0042 |
| GBP_JPY | 3.30 | 0.016500 | 0.786 | 1.573 | 0.016 | -0.0365 |
| GBP_USD | 1.80 | 0.000090 | 0.667 | 1.334 | -0.0123 | -0.0081 |
| USD_CAD | 1.80 | 0.000090 | 0.647 | 1.295 | 0.0071 | -0.0291 |

## Ligne Java a ajouter a RealCostModel.HALF_SPREAD_PRICE

```java
"GBP_CHF", 0.00012500000000004174,
"EUR_CHF", 7.999999999996898e-05,
"AUD_CHF", 7.500000000004725e-05,
```
