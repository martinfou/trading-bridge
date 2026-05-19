# Catalogue des stratégies StrategyQuant (GBPJPY H1)

Backtest: 2003.08.04 - 2024.07.11 | GBPJPY_M1_dukas | H1 timeframe
Généré par StrategyQuant X Build 141 - Optimization run
Toutes les stratégies sont Long Only, BUYSTOP entry.

## Légende des familles

| Code | Famille | Indicateurs |
|------|---------|-------------|
| A | ADX + BB Range + HighestInRange | ADX(14) rising + Highest(price,period) + BB |
| B | Keltner Channel breakout | Close > KC(upper) entry |
| C | Keltner narrowing | KC narrowing + Big/BBSmallest Range |
| D | ADX Hump (up then down) | ADX peak pattern |
| E | Vortex Indicator | Vortex +1 > Vortex -1 |
| F | Linear Regression | LinReg rising + uptrend |
| G | Ichimoku + ADX Hump | Ichimoku cloud + ADX hump |
| H | SuperTrend | SuperTrend UP trend |
| I | LinReg Cross | Cross below LinReg |
| J | Open/Keltner | Open < KC(upper) |
| K | Vortex + Trailing | Vortex + multiple exits |
| L | Daily High + Keltner | Close > KC(upper) + Daily HIGH |

## Toutes les stratégies (par ordre de version)

| Version | Famille | Signal clé | Entrée | SL | PT | R:R |
|---------|---------|-----------|--------|----|----|-----|
| 1.1.141 | A | ADX(14) rising + Highest(OPEN,10) | BBUpper(50,2.1)[1]+0.6*BBRange(20,2) | 150 | 215 | 1.4 |
| 1.3.177 | A | ADX(14) rising + Highest(CLOSE,10) | BBUpper(50,2)[1]+0.6*BBRange(50,1.3) | 150 | 365 | 2.4 |
| 1.3.187 | B | Close > Keltner(55,2) | BB(10,2)[2]+2.4*ATR(40) | **190** | 245 | 1.3 |
| 1.4.116 | B | Close > Keltner(117,2) | KC(104,2.6)[2]+2.4*ATR(50) | 180 | **385** | **2.1** |
| 1.4.120 | B | Close > Keltner(20,2.25) | BB(41,2.4)[1]+2.4*ATR(50) | 180 | 385 | **2.1** |
| 1.4.156 | C | Close > Keltner(131,2.5) | HighestInRange(11:00,7:30,1)+0.3*BBRange(20,2.1) | **95** | **400** | **4.2** |
| 1.4.166 | C | Keltner(58,2.25) falling | HighestInRange(8:30,5:00,1)+2.3*BigRange(5) | 120 | 395 | 3.3 |
| 1.4.180 | F | Uptrend(SMA200) + LinReg(30) rising | Highest(CLOSE,10,3)+2.9*ATR(30) | 130 | 295 | 2.3 |
| 1.5.121 | D | ADX(50) hump (up>down) | Close[3]+1.5*BBRange(10,2) | **195** | 325 | 1.7 |
| 1.5.193 | B | Close > Keltner(117,1.5) + ATR rising | DailyHIGH[1]+0.9*ATR(50) | 115 | 375 | 3.3 |
| 1.6.133 | A | Open < DailyHIGH + HighestInRange(2:30,8:00)/CLOSE(10) | HIGH[1]+0.6*BBRange(50,1.3) | 175 | 365 | 2.1 |
| 1.6.153 | C | HighestInRange(12:00,22:00)/OPEN cross | KC(85,2)[3]+2.9*BarRange[2] | 170 | 290 | 1.7 |
| 1.6.176 | D | ADX(50) hump (up>down) | Close[2]+1.5*BBRange(10,2) | 180 | 325 | 1.8 |
| 1.7.117 | G | ADX(30) hump + Ichimoku | Ichimoku(9,26,52)[2]+1.5*BBRange(20,2) | **195** | 325 | 1.7 |
| 1.7.126 | A | ADX(14) rising + HighestInRange(12:30,9:00)/CLOSE(20) | BB(50,2)[3]+0.6*BBRange(50,1.3) | 150 | 365 | 2.4 |
| 1.7.133 | E | Vortex(30) +1 > -1 | Highest(TP,50,3)+1.9*SmallestRange(50) | 135 | 335 | 2.5 |
| 1.7.165 | F | Uptrend(SMA200) + LinReg(30) rising | HIGH[2]+2.9*ATR(57) | 130 | 295 | 2.3 |
| 1.7.167 | J | Open[3] < KC(20,1.5)[4] | HighestInRange(0,4,3)+0.9*BigRange(27) | 110 | 335 | 3.0 |
| 1.8.123 | A | ADX(20) rising + HighestInRange(12:30,9:00)/CLOSE(20) | BB(50,2)[3]+0.6*BBRange(50,1.3) | 150 | 365 | 2.4 |
| 1.8.147 | B | Close[3] > Keltner(117,2.5) | HighestInRange(7,13,3)+0.7*ATR(45) | 130 | 385 | **2.96** |
| 1.8.187 | G | ADX(14) hump + Ichimoku | Ichimoku(9,26,52)[3]+1.5*BBRange(20,2) | **195** | 325 | 1.7 |
| 1.9.111 | H | SuperTrend uptrend | Highest(HIGH,30,1)+1.7*SmallestRange(77) | 155 | 315 | 2.0 |
| 1.9.186 | B | Close[3] > Keltner(117,2) | HighestInRange(11,4:30,3)+0.7*ATR(45) | 130 | 385 | **2.96** |
| 1.10.113 | B | Close[2] > Keltner(20,0.2) | Close[2]+2.7*ATR(40) | 185 | 355 | 1.9 |
| 1.10.168 | A | ADX(14) rising + Highest(WC,50) | HIGH[2]+0.6*BBRange(50,1.3) | 150 | 365 | 2.4 |
| 1.10.173 | K | Vortex(10) +1 > -1 | Highest(CLOSE,40,1)+2.3*ATR(23)+(Trailing) | **195** | 210 | 1.1 |
| 1.10.186 | I | Open[2] < LinReg(40) && Open[1] > LinReg(40) | BB(20,2)[1]+2.7*ATR(20) | 135 | 230 | 1.7 |
| 1.12.123 | L | Close[2] > Keltner(20,1.5) | Highest(OPEN,5,1)+2.4*ATR(66) | 125 | **385** | **3.1** |
| 1.12.161 | D | ADX(57) hump (up>down) | BB(20,0.9)[2]+2.4*ATR(40) | 190 | 320 | 1.7 |
| 1.12.173 | A | ADX(14) rising + HighestInRange/LOW(14) | BB(50,1.9)[3]+0.6*BBRange(50,1.3) | 150 | 365 | 2.4 |

## Top 3 meilleur ratio Risque/Récompense

1. 🥇 1.4.156 (Famille C) — SL 95 | PT 400 | R:R **4.2** ⭐
2. 🥇 1.12.123 (Famille L) — SL 125 | PT 385 | R:R **3.1**
3. 🥇 1.7.167 (Famille J) — SL 110 | PT 335 | R:R **3.0**

## Top 3 par diversité d'indicateurs

1. 🏆 1.7.133 — Vortex Indicator (seul avec ce signal)
2. 🏆 1.7.117 — Ichimoku Cloud (rare, complexe)
3. 🏆 1.9.111 — SuperTrend (seul à l'utiliser)
