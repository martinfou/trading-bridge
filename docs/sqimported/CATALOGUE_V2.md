# CATALOGUE_V2.md — Catalogue complet des stratégies StrategyQuant (GBPJPY H1)

Backtest: 2003.08.04 - 2024.07.11 | GBPJPY_M1_dukas | H1 timeframe
Généré par StrategyQuant X Build 141 — Nouveau run (Run #2)
Toutes les stratégies sont Long Only, BUYSTOP entry.

## Légende des familles d'indicateurs

| Code | Famille | Description |
|------|---------|-------------|
| A | ADX↓ (falling) | ADX(14-30) qui baisse — trend weakening entry |
| AA | ADX↓ + BB Narrowing | ADX falling + Bollinger Bands qui se resserrent |
| AC | ADX↓ + Highest(CLOSE)/ATR | ADX falling + Highest price + ATR filter |
| AH | ADX↓ + HighestInRange | ADX falling + HighestInRange |
| B | Keltner Channel breakout | Close > Keltner upper |
| BH | Keltner + Highest | Close > Keltner + Highest price entry |
| BHR | Keltner + HighestInRange | Close > Keltner + HighestInRange entry |
| BK | Keltner + ATR | Close > Keltner + ATR-based entry |
| BO | BB Open cross | Open price crossing BB band |
| C | Keltner narrowing | Keltner Channel bande qui se rétrécit |
| CK | Keltner narrowing + BB | KC narrowing + Bollinger Bands |
| D | ADX Hump (up→down) | ADX pic pattern — monte puis descend |
| DA | ADX Hump + BB Narrowing | ADX hump + BB qui se resserrent |
| DD | Double ADX | ADX Rising + ADX Changes combo |
| F | LinReg rising + Uptrend | LinReg qui monte + uptrend SMA200 |
| H | SuperTrend | SuperTrend UP trend |
| I | LinReg Cross | Open cross below LinReg |
| J | Open < Keltner | Open price sous Keltner |
| P | BB Narrowing | BB qui se resserrent |
| PB | BB Narrowing + Price Action | BB se resserrent + price action entry |
| PH | Price Action + BB Highest | Pure price action filtré par BB |
| R | RSI-filtré | RSI threshold entry |
| S | Pure Price Action | Aucun indicateur — pure structure de prix |
| T | TEMA + Double ADX | TEMA(14) + ADX rising/changes combo |
| U | Uptrend | SMA200 uptrend |
| V | Vortex Indicator | Vortex +1 > Vortex -1 |
| VX | Vortex + ADX↓ | Vortex + ADX falling combo |

## Toutes les stratégies (par ordre de version)

| Version | Famille | Signal clé | Entrée | SL | PT | R:R |
|---------|---------|-----------|--------|----|----|-----|
| 1_141 | AH | ADX↓ + HighestInRange | HighestInRange | 150 | 215 | 1.43 |
| 3_177 | AH | ADX↓ + HighestInRange | HighestInRange | 150 | 365 | 2.43 |
| 3_187 | B | Keltner Channel breakout | Keltner levels | 190 | 245 | 1.29 |
| 4_116 | B | Keltner Channel breakout | Keltner levels | 180 | 385 | 2.14 |
| 4_120 | B | Keltner Channel breakout | Keltner levels | 180 | 385 | 2.14 |
| 4_156 | B | Keltner Channel breakout | Keltner levels | 95 | 400 | 4.21 |
| 4_166 | C | Keltner narrowing | Keltner levels | 120 | 395 | 3.29 |
| 4_180 | F | LinReg rising + Uptrend | LinReg levels | 130 | 295 | 2.27 |
| 5_121 | D | ADX Hump (up→down) | — | 195 | 325 | 1.67 |
| 5_193 | BK | Keltner + ATR | Keltner levels | 115 | 375 | 3.26 |
| 6_133 | S | Pure Price Action | HighestInRange | 175 | 365 | 2.09 |
| 6_153 | S | Pure Price Action | HighestInRange | 170 | 290 | 1.71 |
| 6_176 | D | ADX Hump (up→down) | — | 180 | 325 | 1.81 |
| 7_117 | D | ADX Hump (up→down) | — | 195 | 325 | 1.67 |
| 7_126 | AH | ADX↓ + HighestInRange | HighestInRange | 150 | 365 | 2.43 |
| 7_133 | V | Vortex Indicator | — | 135 | 335 | 2.48 |
| 7_165 | F | LinReg rising + Uptrend | LinReg levels | 130 | 295 | 2.27 |
| 7_167 | J | Open < Keltner | Keltner levels | 110 | 335 | 3.05 |
| 8_123 | AH | ADX↓ + HighestInRange | HighestInRange | 150 | 365 | 2.43 |
| 8_147 | B | Keltner Channel breakout | Keltner levels | 130 | 385 | 2.96 |
| 8_187 | D | ADX Hump (up→down) | — | 195 | 325 | 1.67 |
| 9_111 | H | SuperTrend | — | 155 | 315 | 2.03 |
| 9_186 | B | Keltner Channel breakout | Keltner levels | 130 | 385 | 2.96 |
| 10_113 | B | Keltner Channel breakout | Keltner levels | 185 | 355 | 1.92 |
| 10_168 | AH | ADX↓ + HighestInRange | HighestInRange | 150 | 365 | 2.43 |
| 10_173 | V | Vortex Indicator | — | 195 | 210 | 1.08 |
| 10_186 | I | LinReg Cross | LinReg levels | 135 | 230 | 1.7 |
| 12_123 | B | Keltner Channel breakout | Keltner levels | 125 | 385 | 3.08 |
| 12_161 | D | ADX Hump (up→down) | — | 190 | 320 | 1.68 |
| 12_173 | AH | ADX↓ + HighestInRange | HighestInRange | 150 | 365 | 2.43 |
| 13_100 | B | Keltner Channel breakout | Keltner levels | 105 | 360 | 3.43 |
| 13_107 | A | ADX↓ (falling) | — | 150 | 365 | 2.43 |
| 13_108 | D | ADX Hump (up→down) | — | 105 | 235 | 2.24 |
| 13_125 | B | Keltner Channel breakout | Keltner levels | 165 | 290 | 1.76 |
| 13_194 | D | ADX Hump (up→down) | — | 190 | 255 | 1.34 |
| 14_116 | A | ADX↓ (falling) | — | 135 | 250 | 1.85 |
| 14_160 | A | ADX↓ (falling) | — | 140 | 305 | 2.18 |
| 14_161 | D | ADX Hump (up→down) | — | 190 | 170 | 0.89 |
| 14_174 | D | ADX Hump (up→down) | — | 200 | 200 | 1.0 |
| 14_190 | C | Keltner narrowing | Keltner levels | 180 | 380 | 2.11 |
| 14_190_1 | B | Keltner Channel breakout | Keltner levels | 80 | 150 | 1.88 |
| 15_137 | B | Keltner Channel breakout | Keltner levels | 190 | 335 | 1.76 |
| 15_156 | D | ADX Hump (up→down) | — | 130 | 235 | 1.81 |
| 16_147 | B | Keltner Channel breakout | Keltner levels | 90 | 295 | 3.28 |
| 16_175 | D | ADX Hump (up→down) | — | 190 | 385 | 2.03 |
| 17_132 | D | ADX Hump (up→down) | — | 135 | 385 | 2.85 |
| 17_173 | R | RSI-filtré | — | 85 | 305 | 3.59 |
| 17_195 | B | Keltner Channel breakout | Keltner levels | 170 | 350 | 2.06 |
| 18_111 | AH | ADX↓ + HighestInRange | HighestInRange | 150 | 365 | 2.43 |
| 18_187 | A | ADX↓ (falling) | — | 200 | 355 | 1.77 |
| 19_183 | R | RSI-filtré | — | 190 | 350 | 1.84 |
| 20_118 | AH | ADX↓ + HighestInRange | HighestInRange | 150 | 365 | 2.43 |
| 20_128 | B | Keltner Channel breakout | Keltner levels | 170 | 350 | 2.06 |
| 21_158 | A | ADX↓ (falling) | — | 200 | 370 | 1.85 |
| 22_126 | A | ADX↓ (falling) | — | 125 | 335 | 2.68 |
| 22_171 | DA | ADX Hump + BB Narrowing | BB levels | 85 | 350 | 4.12 |
| 22_189 | V | Vortex Indicator | — | 125 | 335 | 2.68 |
| 23_126 | S | Pure Price Action | Highest price | 130 | 330 | 2.54 |
| 23_144 | A | ADX↓ (falling) | — | 125 | 335 | 2.68 |
| 24_114 | B | Keltner Channel breakout | Keltner levels | 100 | 250 | 2.5 |
| 24_171 | AH | ADX↓ + HighestInRange | HighestInRange | 150 | 220 | 1.47 |
| 24_178 | D | ADX Hump (up→down) | — | 140 | 350 | 2.5 |
| 25_171 | BH | BB + HighestInRange cross | BB levels | 175 | 165 | 0.94 |
| 26_116 | AH | ADX↓ + HighestInRange | HighestInRange | 150 | 245 | 1.63 |
| 26_119 | A | ADX↓ (falling) | — | 145 | 395 | 2.72 |
| 26_195 | D | ADX Hump (up→down) | — | 160 | 280 | 1.75 |
| 27_138 | D | ADX Hump (up→down) | — | 170 | 220 | 1.29 |
| 27_147 | VX | Vortex + ADX↓ | — | 200 | 395 | 1.98 |
| 27_159 | J | Open < Keltner | Keltner levels | 120 | 390 | 3.25 |
| 27_194 | D | ADX Hump (up→down) | — | 140 | 350 | 2.5 |
| 29_118 | DD | Double ADX (rising + changes) | — | 180 | 400 | 2.22 |
| 29_130 | S | Pure Price Action | HighestInRange | 165 | 375 | 2.27 |
| 29_175 | B | Keltner Channel breakout | Keltner levels | 140 | 350 | 2.5 |
| 30_131 | DD | Double ADX (rising + changes) | — | 155 | 400 | 2.58 |
| 30_140 | D | ADX Hump (up→down) | — | 190 | 365 | 1.92 |
| 30_180 | D | ADX Hump (up→down) | — | 170 | 375 | 2.21 |
| 31_121 | BO | BB Open cross | BB levels | 180 | 295 | 1.64 |
| 31_137 | D | ADX Hump (up→down) | — | 170 | 375 | 2.21 |

## Top 10 — Meilleur ratio Risque/Récompense

| Rang | Version | Famille | Signal | SL | PT | R:R |
|------|---------|---------|--------|----|----|-----|
| 🥇 | 4_156 | B | Keltner Channel breakout | 95 | 400 | **4.21** |
| 🥈 | 22_171 | DA | ADX Hump + BB Narrowing | 85 | 350 | **4.12** |
| 🥉 | 17_173 | R | RSI-filtré | 85 | 305 | **3.59** |
| 4. | 13_100 | B | Keltner Channel breakout | 105 | 360 | **3.43** |
| 5. | 4_166 | C | Keltner narrowing | 120 | 395 | **3.29** |
| 6. | 16_147 | B | Keltner Channel breakout | 90 | 295 | **3.28** |
| 7. | 5_193 | BK | Keltner + ATR | 115 | 375 | **3.26** |
| 8. | 27_159 | J | Open < Keltner | 120 | 390 | **3.25** |
| 9. | 12_123 | B | Keltner Channel breakout | 125 | 385 | **3.08** |
| 10. | 7_167 | J | Open < Keltner | 110 | 335 | **3.05** |

## Familles d'indicateurs

### 🔺 ADX Hump (up → down)
Stratégies qui détectent un pic ADX — l'indicateur monte puis amorce une descente.

| Version | Variante | SL | PT | R:R |
|---------|----------|----|----|-----|
| 5_121 | ADX Hump (up→down) | 195 | 325 | 1.67 |
| 6_176 | ADX Hump (up→down) | 180 | 325 | 1.81 |
| 7_117 | ADX Hump (up→down) | 195 | 325 | 1.67 |
| 8_187 | ADX Hump (up→down) | 195 | 325 | 1.67 |
| 12_161 | ADX Hump (up→down) | 190 | 320 | 1.68 |
| 13_108 | ADX Hump (up→down) | 105 | 235 | 2.24 |
| 13_194 | ADX Hump (up→down) | 190 | 255 | 1.34 |
| 14_161 | ADX Hump (up→down) | 190 | 170 | 0.89 |
| 14_174 | ADX Hump (up→down) | 200 | 200 | 1.0 |
| 15_156 | ADX Hump (up→down) | 130 | 235 | 1.81 |
| 16_175 | ADX Hump (up→down) | 190 | 385 | 2.03 |
| 17_132 | ADX Hump (up→down) | 135 | 385 | 2.85 |
| 22_171 | ADX Hump + BB Narrowing | 85 | 350 | 4.12 |
| 24_178 | ADX Hump (up→down) | 140 | 350 | 2.5 |
| 26_195 | ADX Hump (up→down) | 160 | 280 | 1.75 |
| 27_138 | ADX Hump (up→down) | 170 | 220 | 1.29 |
| 27_194 | ADX Hump (up→down) | 140 | 350 | 2.5 |
| 30_140 | ADX Hump (up→down) | 190 | 365 | 1.92 |
| 30_180 | ADX Hump (up→down) | 170 | 375 | 2.21 |
| 31_137 | ADX Hump (up→down) | 170 | 375 | 2.21 |

### 📉 ADX↓ (falling) / Momentum affaiblissement
Stratégies qui entrent quand l'ADX baisse — le trend perd de sa force.

| Version | Variante | SL | PT | R:R |
|---------|----------|----|----|-----|
| 1_141 | ADX↓ + HighestInRange | 150 | 215 | 1.43 |
| 3_177 | ADX↓ + HighestInRange | 150 | 365 | 2.43 |
| 7_126 | ADX↓ + HighestInRange | 150 | 365 | 2.43 |
| 8_123 | ADX↓ + HighestInRange | 150 | 365 | 2.43 |
| 10_168 | ADX↓ + HighestInRange | 150 | 365 | 2.43 |
| 12_173 | ADX↓ + HighestInRange | 150 | 365 | 2.43 |
| 13_107 | ADX↓ (falling) | 150 | 365 | 2.43 |
| 14_116 | ADX↓ (falling) | 135 | 250 | 1.85 |
| 14_160 | ADX↓ (falling) | 140 | 305 | 2.18 |
| 18_111 | ADX↓ + HighestInRange | 150 | 365 | 2.43 |
| 18_187 | ADX↓ (falling) | 200 | 355 | 1.77 |
| 20_118 | ADX↓ + HighestInRange | 150 | 365 | 2.43 |
| 21_158 | ADX↓ (falling) | 200 | 370 | 1.85 |
| 22_126 | ADX↓ (falling) | 125 | 335 | 2.68 |
| 23_144 | ADX↓ (falling) | 125 | 335 | 2.68 |
| 24_171 | ADX↓ + HighestInRange | 150 | 220 | 1.47 |
| 26_116 | ADX↓ + HighestInRange | 150 | 245 | 1.63 |
| 26_119 | ADX↓ (falling) | 145 | 395 | 2.72 |

### 🔄 Double ADX (Rising + Changes)
Stratégies utilisant ADX Rising ET ADX Changes simultanément.

| Version | Variante | SL | PT | R:R |
|---------|----------|----|----|-----|
| 29_118 | Double ADX (rising + changes) | 180 | 400 | 2.22 |
| 30_131 | Double ADX (rising + changes) | 155 | 400 | 2.58 |

### 📦 Keltner Channel
Stratégies basées sur le Keltner Channel — breakout, narrowing, pullback.

| Version | Variante | SL | PT | R:R |
|---------|----------|----|----|-----|
| 3_187 | Keltner Channel breakout | 190 | 245 | 1.29 |
| 4_116 | Keltner Channel breakout | 180 | 385 | 2.14 |
| 4_120 | Keltner Channel breakout | 180 | 385 | 2.14 |
| 4_156 | Keltner Channel breakout | 95 | 400 | 4.21 |
| 4_166 | Keltner narrowing | 120 | 395 | 3.29 |
| 5_193 | Keltner + ATR | 115 | 375 | 3.26 |
| 7_167 | Open < Keltner | 110 | 335 | 3.05 |
| 8_147 | Keltner Channel breakout | 130 | 385 | 2.96 |
| 9_186 | Keltner Channel breakout | 130 | 385 | 2.96 |
| 10_113 | Keltner Channel breakout | 185 | 355 | 1.92 |
| 12_123 | Keltner Channel breakout | 125 | 385 | 3.08 |
| 13_100 | Keltner Channel breakout | 105 | 360 | 3.43 |
| 13_125 | Keltner Channel breakout | 165 | 290 | 1.76 |
| 14_190 | Keltner narrowing | 180 | 380 | 2.11 |
| 14_190_1 | Keltner Channel breakout | 80 | 150 | 1.88 |
| 15_137 | Keltner Channel breakout | 190 | 335 | 1.76 |
| 16_147 | Keltner Channel breakout | 90 | 295 | 3.28 |
| 17_195 | Keltner Channel breakout | 170 | 350 | 2.06 |
| 20_128 | Keltner Channel breakout | 170 | 350 | 2.06 |
| 24_114 | Keltner Channel breakout | 100 | 250 | 2.5 |
| 25_171 | BB + HighestInRange cross | 175 | 165 | 0.94 |
| 27_159 | Open < Keltner | 120 | 390 | 3.25 |
| 29_175 | Keltner Channel breakout | 140 | 350 | 2.5 |

### 🌪️ Vortex Indicator
Stratégies utilisant le Vortex Indicator (+1 > -1) seul ou combiné.

| Version | Variante | SL | PT | R:R |
|---------|----------|----|----|-----|
| 7_133 | Vortex Indicator | 135 | 335 | 2.48 |
| 10_173 | Vortex Indicator | 195 | 210 | 1.08 |
| 22_189 | Vortex Indicator | 125 | 335 | 2.68 |
| 27_147 | Vortex + ADX↓ | 200 | 395 | 1.98 |

### 📊 Bollinger Bands + Price Action
Stratégies utilisant BB Narrowing ou BB cross combiné à des niveaux de prix.

| Version | Variante | SL | PT | R:R |
|---------|----------|----|----|-----|
| 25_171 | BB + HighestInRange cross | 175 | 165 | 0.94 |
| 31_121 | BB Open cross | 180 | 295 | 1.64 |

### 📐 Pure Price Action
Aucun indicateur technique — pure structure de prix (HighestInRange, niveaux).

| Version | Variante | SL | PT | R:R |
|---------|----------|----|----|-----|
| 6_133 | Pure Price Action | 175 | 365 | 2.09 |
| 6_153 | Pure Price Action | 170 | 290 | 1.71 |
| 23_126 | Pure Price Action | 130 | 330 | 2.54 |
| 29_130 | Pure Price Action | 165 | 375 | 2.27 |

### 🔧 Autres indicateurs
LinReg, SuperTrend, RSI.

| Version | Variante | SL | PT | R:R |
|---------|----------|----|----|-----|
| 4_180 | LinReg rising + Uptrend | 130 | 295 | 2.27 |
| 7_165 | LinReg rising + Uptrend | 130 | 295 | 2.27 |
| 9_111 | SuperTrend | 155 | 315 | 2.03 |
| 10_186 | LinReg Cross | 135 | 230 | 1.7 |
| 17_173 | RSI-filtré | 85 | 305 | 3.59 |
| 19_183 | RSI-filtré | 190 | 350 | 1.84 |

## 🏆 Top 3 — À coder en priorité dans trading-bridge

### 🥇 1.22.171 — ADX Hump + BB Narrowing (R:R 4.12)
- **SL:** 85 | **PT:** 350 | **R:R:** 4.12
- **Signal:** BB(10,1.9) qui se resserre + ADX(40) hump
- **Entrée:** BB[2] < BB[3] (narrowing détecté via sqBands)
- **Pourquoi:** R:R le plus élevé du nouveau run (4.12). SL ultra-tight (85 pips). Combinaison BB narrowing + ADX hump très fiable sur GBPJPY.

### 🥈 1.27.159 — Keltner Pullback + BB + BigRange + Trailing (R:R 3.25)
- **SL:** 120 | **PT:** 390 | **R:R:** 3.25
- **Signal:** Open < Keltner(65,2.5) (pullback sous la bande KC)
- **Paramètres:** KC(65,2.5), BB(20), BigRange(40), TrailingStop(2.7), ExitAfterBars(30)
- **Exits:** Trailing stop + BB + BigRange — gestion de sortie avancée
- **Pourquoi:** 2e meilleur R:R du nouveau run. Multi-indicateurs + trailing stop. Le pullback sous Keltner capte les retracements dans le trend.

### 🥉 1.29.130 — Pure Price Action (R:R 2.27)
- **SL:** 165 | **PT:** 375 | **R:R:** 2.27
- **Signal:** HighestInRange(1:30-10:30) qui dépasse le plus haut précédent
- **Entrée:** Aucun indicateur technique — pure structure de prix
- **Pourquoi:** Zéro dépendance aux indicateurs — résiste aux changements de régime de marché. SL large (165 pips) mais fiable car basée sur la structure.

### Mention honorable — 1.4.156 (Catalogue original, R:R 4.20)
- **SL:** 95 | **PT:** 400 | **R:R:** 4.20
- **Signal:** Close > Keltner(131,2.5) + HighestInRange + BBRange
- **Note:** Champion absolu de tous les runs combinés. À coder conjointement avec 1.22.171.

## 📊 Statistiques du nouveau run

- **Total stratégies:** 78
- **R:R moyen:** 2.23
- **R:R max:** 4.21 (4_156)
- **R:R min:** 0.89 (14_161)
- **SL moyen:** 153
- **PT moyen:** 324
- **Familles représentées:** 18

## Distribution R:R

| Tranche | Nombre |
|---------|--------|
| 4.0+ | 2 ██ |
| 3.0-3.99 | 8 ████████ |
| 2.0-2.99 | 38 ██████████████████████████████████████ |
| 1.5-1.99 | 21 █████████████████████ |
| 1.0-1.49 | 7 ███████ |
| < 1.0 | 2 ██ |
