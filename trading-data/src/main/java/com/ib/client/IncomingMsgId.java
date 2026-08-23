/*
 * Java TWS API Client
 *
 * Copyright (C) 2013-2026  Interactive Brokers LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.ib.client;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum IncomingMsgId {
    END_CONN(-1),
    TICK_PRICE(1),
    TICK_SIZE(2),
    ORDER_STATUS(3),
    ERR_MSG(4),
    OPEN_ORDER(5),
    ACCT_VALUE(6),
    PORTFOLIO_VALUE(7),
    ACCT_UPDATE_TIME(8),
    NEXT_VALID_ID(9),
    CONTRACT_DATA(10),
    EXECUTION_DATA(11),
    MARKET_DEPTH(12),
    MARKET_DEPTH_L2(13),
    NEWS_BULLETINS(14),
    MANAGED_ACCTS(15),
    RECEIVE_FA(16),
    HISTORICAL_DATA(17),
    BOND_CONTRACT_DATA(18),
    SCANNER_PARAMETERS(19),
    SCANNER_DATA(20),
    TICK_OPTION_COMPUTATION(21),
    TICK_GENERIC(45),
    TICK_STRING(46),
    TICK_EFP(47),
    CURRENT_TIME(49),
    REAL_TIME_BARS(50),
    CONTRACT_DATA_END(52),
    OPEN_ORDER_END(53),
    ACCT_DOWNLOAD_END(54),
    EXECUTION_DATA_END(55),
    DELTA_NEUTRAL_VALIDATION(56),
    TICK_SNAPSHOT_END(57),
    MARKET_DATA_TYPE(58),
    COMMISSION_AND_FEES_REPORT(59),
    POSITION(61),
    POSITION_END(62),
    ACCOUNT_SUMMARY(63),
    ACCOUNT_SUMMARY_END(64),
    VERIFY_MESSAGE_API(65),
    VERIFY_COMPLETED(66),
    DISPLAY_GROUP_LIST(67),
    DISPLAY_GROUP_UPDATED(68),
    VERIFY_AND_AUTH_MESSAGE_API(69),
    VERIFY_AND_AUTH_COMPLETED(70),
    POSITION_MULTI(71),
    POSITION_MULTI_END(72),
    ACCOUNT_UPDATE_MULTI(73),
    ACCOUNT_UPDATE_MULTI_END(74),
    SECURITY_DEFINITION_OPTION_PARAMETER(75),
    SECURITY_DEFINITION_OPTION_PARAMETER_END(76),
    SOFT_DOLLAR_TIERS(77),
    FAMILY_CODES(78),
    SYMBOL_SAMPLES(79),
    MKT_DEPTH_EXCHANGES(80),
    TICK_REQ_PARAMS(81),
    SMART_COMPONENTS(82),
    NEWS_ARTICLE(83),
    TICK_NEWS(84),
    NEWS_PROVIDERS(85),
    HISTORICAL_NEWS(86),
    HISTORICAL_NEWS_END(87),
    HEAD_TIMESTAMP(88),
    HISTOGRAM_DATA(89),
    HISTORICAL_DATA_UPDATE(90),
    REROUTE_MKT_DATA_REQ(91),
    REROUTE_MKT_DEPTH_REQ(92),
    MARKET_RULE(93),
    PNL(94),
    PNL_SINGLE(95),
    HISTORICAL_TICKS(96),
    HISTORICAL_TICKS_BID_ASK(97),
    HISTORICAL_TICKS_LAST(98),
    TICK_BY_TICK(99),
    ORDER_BOUND(100),
    COMPLETED_ORDER(101),
    COMPLETED_ORDERS_END(102),
    REPLACE_FA_END(103),
    WSH_META_DATA(104),
    WSH_EVENT_DATA(105),
    HISTORICAL_SCHEDULE(106),
    USER_INFO(107),
    HISTORICAL_DATA_END(108),
    CURRENT_TIME_IN_MILLIS(109),
    CONFIG_RESPONSE(110),
    UPDATE_CONFIG_RESPONSE(111);

    private final int id;
    private static final Map<Integer, IncomingMsgId> BY_ID =
        Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(e -> e.id, Function.identity()));

    IncomingMsgId(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static IncomingMsgId fromId(int id) {
        return BY_ID.get(id);
    }
}
