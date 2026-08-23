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

public enum OutgoingMsgId {
    REQ_MKT_DATA(1),
    CANCEL_MKT_DATA(2),
    PLACE_ORDER(3),
    CANCEL_ORDER(4),
    REQ_OPEN_ORDERS(5),
    REQ_ACCOUNT_DATA(6),
    REQ_EXECUTIONS(7),
    REQ_IDS(8),
    REQ_CONTRACT_DATA(9),
    REQ_MKT_DEPTH(10),
    CANCEL_MKT_DEPTH(11),
    REQ_NEWS_BULLETINS(12),
    CANCEL_NEWS_BULLETINS(13),
    SET_SERVER_LOGLEVEL(14),
    REQ_AUTO_OPEN_ORDERS(15),
    REQ_ALL_OPEN_ORDERS(16),
    REQ_MANAGED_ACCTS(17),
    REQ_FA(18),
    REPLACE_FA(19),
    REQ_HISTORICAL_DATA(20),
    EXERCISE_OPTIONS(21),
    REQ_SCANNER_SUBSCRIPTION(22),
    CANCEL_SCANNER_SUBSCRIPTION(23),
    REQ_SCANNER_PARAMETERS(24),
    CANCEL_HISTORICAL_DATA(25),
    REQ_CURRENT_TIME(49),
    REQ_REAL_TIME_BARS(50),
    CANCEL_REAL_TIME_BARS(51),
    REQ_CALC_IMPLIED_VOLAT(54),
    REQ_CALC_OPTION_PRICE(55),
    CANCEL_CALC_IMPLIED_VOLAT(56),
    CANCEL_CALC_OPTION_PRICE(57),
    REQ_GLOBAL_CANCEL(58),
    REQ_MARKET_DATA_TYPE(59),
    REQ_POSITIONS(61),
    REQ_ACCOUNT_SUMMARY(62),
    CANCEL_ACCOUNT_SUMMARY(63),
    CANCEL_POSITIONS(64),
    VERIFY_REQUEST(65),
    VERIFY_MESSAGE(66),
    QUERY_DISPLAY_GROUPS(67),
    SUBSCRIBE_TO_GROUP_EVENTS(68),
    UPDATE_DISPLAY_GROUP(69),
    UNSUBSCRIBE_FROM_GROUP_EVENTS(70),
    START_API(71),
    VERIFY_AND_AUTH_REQUEST(72),
    VERIFY_AND_AUTH_MESSAGE(73),
    REQ_POSITIONS_MULTI(74),
    CANCEL_POSITIONS_MULTI(75),
    REQ_ACCOUNT_UPDATES_MULTI(76),
    CANCEL_ACCOUNT_UPDATES_MULTI(77),
    REQ_SEC_DEF_OPT_PARAMS(78),
    REQ_SOFT_DOLLAR_TIERS(79),
    REQ_FAMILY_CODES(80),
    REQ_MATCHING_SYMBOLS(81),
    REQ_MKT_DEPTH_EXCHANGES(82),
    REQ_SMART_COMPONENTS(83),
    REQ_NEWS_ARTICLE(84),
    REQ_NEWS_PROVIDERS(85),
    REQ_HISTORICAL_NEWS(86),
    REQ_HEAD_TIMESTAMP(87),
    REQ_HISTOGRAM_DATA(88),
    CANCEL_HISTOGRAM_DATA(89),
    CANCEL_HEAD_TIMESTAMP(90),
    REQ_MARKET_RULE(91),
    REQ_PNL(92),
    CANCEL_PNL(93),
    REQ_PNL_SINGLE(94),
    CANCEL_PNL_SINGLE(95),
    REQ_HISTORICAL_TICKS(96),
    REQ_TICK_BY_TICK_DATA(97),
    CANCEL_TICK_BY_TICK_DATA(98),
    REQ_COMPLETED_ORDERS(99),
    REQ_WSH_META_DATA(100),
    CANCEL_WSH_META_DATA(101),
    REQ_WSH_EVENT_DATA(102),
    CANCEL_WSH_EVENT_DATA(103),
    REQ_USER_INFO(104),
    REQ_CURRENT_TIME_IN_MILLIS(105),
    CANCEL_CONTRACT_DATA(106),
    CANCEL_HISTORICAL_TICKS(107),
    REQ_CONFIG(108),
    UPDATE_CONFIG(109);

    private final int id;

    OutgoingMsgId(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }
}
