#!/usr/bin/env python3
"""
Multi-Asset Backtest — 11 Creative Strategies × 9 Assets
Efficient O(n) single-pass backtest avec Robustness Factor.
"""

import struct
import os
import math
import json
from datetime import datetime, timezone
from collections import deque

BARS_DIR = "/home/martinfou/projects/trading-bridge/data/historical/bars"
OUT_DIR = "/home/martinfou/projects/trading-bridge/creative-lab"
CAPITAL = 50000.0
COMMISSION_PCT = 0.0007  # 0.7 pip
SLIPPAGE_PCT = 0.0003

ASSETS = [
    "GBP_JPY", "XAU_USD", "EUR_USD", "USD_CAD", "GBP_USD",
    "USD_JPY", "AUD_USD", "NZD_USD", "USD_CHF"
]

class RunningIndicators:
    """O(1) running indicators — single pass, no recalculation."""
    
    def __init__(self, period=14):
        self.period = period
        self.values = deque(maxlen=period)
        self.sum = 0.0
        self.count = 0
    
    def add(self, val):
        if len(self.values) == self.period:
            self.sum -= self.values[0]
        self.values.append(val)
        self.sum += val
        self.count += 1
    
    @property
    def ready(self):
        return len(self.values) == self.period
    
    @property
    def mean(self):
        return self.sum / len(self.values) if self.values else 0.0
    
    @property
    def sma(self):
        return self.mean if self.ready else None
    
    def std(self, ddof=0):
        if not self.ready:
            return None
        m = self.mean
        var = sum((x - m) ** 2 for x in self.values) / (len(self.values) - ddof) if ddof else sum((x - m) ** 2 for x in self.values) / len(self.values)
        return math.sqrt(var)


class RunningEMA:
    def __init__(self, period):
        self.period = period
        self.alpha = 2.0 / (period + 1)
        self.value = None
    
    def add(self, val):
        if self.value is None:
            self.value = val
        else:
            self.value = self.alpha * val + (1 - self.alpha) * self.value
    
    @property
    def ready(self):
        return self.value is not None


class RunningATR:
    def __init__(self, period=14):
        self.period = period
        self.ema = RunningEMA(period)
        self.prev_close = None
        self.count = 0
    
    def add(self, high, low, close):
        if self.prev_close is None:
            tr = high - low
        else:
            tr = max(high - low, abs(high - self.prev_close), abs(low - self.prev_close))
        self.ema.add(tr)
        self.prev_close = close
        self.count += 1
    
    @property
    def ready(self):
        return self.count >= self.period
    
    @property
    def value(self):
        return self.ema.value if self.ready else None


class RunningBB:
    def __init__(self, period=20, std_dev=2.0):
        self.period = period
        self.std_dev = std_dev
        self.sma = RunningIndicators(period)
    
    def add(self, val):
        self.sma.add(val)
    
    @property
    def ready(self):
        return self.sma.ready
    
    @property
    def upper(self):
        return self.sma.mean + self.std_dev * self.sma.std()
    
    @property
    def lower(self):
        return self.sma.mean - self.std_dev * self.sma.std()
    
    @property
    def middle(self):
        return self.sma.mean
    
    @property
    def bandwidth(self):
        return (self.upper - self.lower) / self.middle if self.middle != 0 else 0


class RunningLinReg:
    def __init__(self, period=14):
        self.period = period
        self.x_vals = deque(maxlen=period)
        self.y_vals = deque(maxlen=period)
    
    def add(self, val):
        self.x_vals.append(len(self.y_vals))
        self.y_vals.append(val)
    
    @property
    def ready(self):
        return len(self.y_vals) == self.period
    
    @property
    def slope(self):
        if not self.ready:
            return 0
        n = self.period
        sum_x = sum(self.x_vals)
        sum_y = sum(self.y_vals)
        sum_xy = sum(x * y for x, y in zip(self.x_vals, self.y_vals))
        sum_xx = sum(x * x for x in self.x_vals)
        return (n * sum_xy - sum_x * sum_y) / (n * sum_xx - sum_x * sum_x) if (n * sum_xx - sum_x * sum_x) != 0 else 0
    
    @property
    def intercept(self):
        if not self.ready:
            return 0
        n = self.period
        return (sum(self.y_vals) - self.slope * sum(self.x_vals)) / n
    
    @property
    def value(self):
        if not self.ready:
            return None
        return self.intercept + self.slope * self.period


class RunningVortex:
    """Vortex Indicator — uses high/low/close."""
    def __init__(self, period=14):
        self.period = period
        self.vm_plus = RunningEMA(period)
        self.vm_minus = RunningEMA(period)
        self.tr_ema = RunningEMA(period)
        self.prev_high = None
        self.prev_low = None
        self.prev_close = None
    
    def add(self, high, low, close):
        if self.prev_high is not None:
            vm_p = abs(high - self.prev_low)
            vm_m = abs(low - self.prev_high)
            tr = max(high - low, abs(high - self.prev_close), abs(low - self.prev_close))
            self.vm_plus.add(vm_p)
            self.vm_minus.add(vm_m)
            self.tr_ema.add(tr)
        
        self.prev_high = high
        self.prev_low = low
        self.prev_close = close
    
    @property
    def ready(self):
        return self.tr_ema.ready
    
    @property
    def vi_plus(self):
        return self.vm_plus.value / self.tr_ema.value if self.ready and self.tr_ema.value else 0
    
    @property
    def vi_minus(self):
        return self.vm_minus.value / self.tr_ema.value if self.ready and self.tr_ema.value else 0


class RunningHighest:
    def __init__(self, period):
        self.period = period
        self.values = deque(maxlen=period)
    
    def add(self, val):
        self.values.append(val)
    
    @property
    def ready(self):
        return len(self.values) == self.period
    
    @property
    def value(self):
        return max(self.values) if self.values else 0


class RunningLowest:
    def __init__(self, period):
        self.period = period
        self.values = deque(maxlen=period)
    
    def add(self, val):
        self.values.append(val)
    
    @property
    def ready(self):
        return len(self.values) == self.period
    
    @property
    def value(self):
        return min(self.values) if self.values else 0


def read_bars(symbol):
    """Read .bars file (Big-Endian, milliseconds)."""
    path = os.path.join(BARS_DIR, f"{symbol}_H1_H1.bars")
    if not os.path.exists(path):
        return []
    
    with open(path, 'rb') as f:
        data = f.read()
    
    bars = []
    for i in range(0, len(data), 44):
        chunk = data[i:i+44]
        if len(chunk) < 44:
            break
        ts_ms, o, h, lo, c, v = struct.unpack('>qddddi', chunk)
        bars.append((ts_ms, o, h, lo, c, v))
    
    return bars


class BacktestResult:
    def __init__(self):
        self.trades = 0
        self.wins = 0
        self.losses = 0
        self.total_return = 0.0
        self.equity_curve = [CAPITAL]
        self.max_equity = CAPITAL
        self.max_dd = 0.0
        self.gross_profit = 0.0
        self.gross_loss = 0.0


def calc_metrics(result, capital=CAPITAL):
    """Calculate performance metrics from a BacktestResult."""
    trades = result.trades
    if trades == 0:
        return {'trades': 0, 'return_pct': 0, 'sharpe': 0, 'win_rate': 0, 'pf': 0, 'max_dd': 0}
    
    eq = result.equity_curve
    
    # Returns
    returns = []
    for i in range(1, len(eq)):
        if eq[i-1] > 0:
            returns.append((eq[i] - eq[i-1]) / eq[i-1])
    
    # Sharpe (annualized, H1 bars)
    if len(returns) > 1:
        avg_r = sum(returns) / len(returns)
        std_r = math.sqrt(sum((r - avg_r) ** 2 for r in returns) / (len(returns) - 1))
        sharpe = (avg_r / std_r * math.sqrt(365 * 24)) if std_r > 0 else 0
    else:
        sharpe = 0
    
    # Return
    return_pct = (eq[-1] - capital) / capital * 100
    
    # Win rate
    wr = result.wins / trades * 100 if trades > 0 else 0
    
    # Profit factor
    pf = result.gross_profit / result.gross_loss if result.gross_loss > 0 else (result.gross_profit if result.gross_profit > 0 else 0)
    
    # Max drawdown
    max_dd = 0
    peak = capital
    for e in eq:
        if e > peak:
            peak = e
        dd = (peak - e) / peak * 100
        if dd > max_dd:
            max_dd = dd
    
    return {
        'trades': trades,
        'return_pct': round(return_pct, 2),
        'sharpe': round(sharpe, 2),
        'win_rate': round(wr, 1),
        'pf': round(pf, 2),
        'max_dd': round(max_dd, 2),
    }


# ============================================================
# STRATEGY IMPLEMENTATIONS
# Each implements: on_bar(ts, o, h, l, c, v) → (entry_price, direction, sl, tp)
# ============================================================

class FridayBearStrategy:
    """Vendre le vendredi en fin de semaine — saisonnalité bearish weekend."""
    
    def __init__(self):
        self.position = 0  # 0=none, -1=short, 1=long
        self.entry_price = 0
        self.sl = 0
        self.tp = 0
        self.bars_since_entry = 0
        self.max_bars = 48
    
    def reset(self):
        self.position = 0
        self.bars_since_entry = 0
    
    def on_bar(self, ts_ms, o, h, l, c, v):
        dt = datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc)
        hour_utc2 = (dt.hour + 2) % 24  # UTC+2
        
        if self.position != 0:
            self.bars_since_entry += 1
            # Exit: end of week (Friday 21:00 UTC+2 = 19:00 UTC)
            if dt.weekday() == 4 and hour_utc2 >= 19:
                self.position = 0
            # Stop loss
            if self.entry_price > 0 and self.position == -1:
                if c >= self.sl:
                    self.position = 0
            if self.bars_since_entry >= self.max_bars:
                self.position = 0
            return None
        
        # Entry: Thursday 14:00 UTC+2, sell Friday expectation
        if dt.weekday() == 3 and 10 <= hour_utc2 <= 14:
            self.position = -1
            self.entry_price = c
            self.sl = c * 1.015  # 1.5% stop
            self.tp = c * 0.985
            self.bars_since_entry = 0
            return ('sell', c, self.sl, self.tp)
        
        return None


class GapFaderStrategy:
    """Fade les gaps entre le close précédent et l'open actuel."""
    
    def __init__(self):
        self.prev_close = None
        self.position = 0
        self.entry_price = 0
        self.sl = 0
        self.bars_since_entry = 0
        self.max_bars = 12
    
    def on_bar(self, ts_ms, o, h, l, c, v):
        if self.position != 0:
            self.bars_since_entry += 1
            if self.bars_since_entry >= self.max_bars:
                self.position = 0
            if self.position == 1 and c <= self.sl:
                self.position = 0
            elif self.position == -1 and c >= self.sl:
                self.position = 0
            return None
        
        if self.prev_close is not None:
            gap_pct = (o - self.prev_close) / self.prev_close * 100
            # Gap up > 0.3% → fade (short)
            if gap_pct > 0.3:
                self.position = -1
                self.entry_price = o
                self.sl = o * 1.01
                self.bars_since_entry = 0
                self.prev_close = c
                return ('sell', o, self.sl, None)
            # Gap down > 0.3% → fade (long)
            elif gap_pct < -0.3:
                self.position = 1
                self.entry_price = o
                self.sl = o * 0.99
                self.bars_since_entry = 0
                self.prev_close = c
                return ('buy', o, self.sl, None)
        
        self.prev_close = c
        return None


class LondonOpenVolStrategy:
    """Trade la volatilité à l'ouverture de Londres."""
    
    def __init__(self):
        self.position = 0
        self.entry_price = 0
        self.sl = 0
        self.tp = 0
        self.atr = RunningATR(20)
        self.sma50 = RunningIndicators(50)
        self.bars_since_entry = 0
        self.max_bars = 24
        self.bar_count = 0
    
    def on_bar(self, ts_ms, o, h, l, c, v):
        self.atr.add(h, l, c)
        self.sma50.add(c)
        self.bar_count += 1
        
        if self.position != 0:
            self.bars_since_entry += 1
            if self.bars_since_entry >= self.max_bars:
                self.position = 0
            if self.position == 1 and c <= self.sl:
                self.position = 0
            elif self.position == -1 and c >= self.sl:
                self.position = 0
            return None
        
        dt = datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc)
        hour_utc2 = (dt.hour + 2) % 24
        
        if not self.atr.ready or not self.sma50.ready:
            return None
        
        # London open: 09:00-10:00 UTC+2
        if 9 <= hour_utc2 <= 10:
            atr_val = self.atr.value
            
            if c > self.sma50.mean and (h - l) > atr_val * 1.2:
                self.position = 1
                self.entry_price = c
                self.sl = c - atr_val * 1.5
                self.tp = c + atr_val * 2.0
                self.bars_since_entry = 0
                return ('buy', c, self.sl, self.tp)
            elif c < self.sma50.mean and (h - l) > atr_val * 1.2:
                self.position = -1
                self.entry_price = c
                self.sl = c + atr_val * 1.5
                self.tp = c - atr_val * 2.0
                self.bars_since_entry = 0
                return ('sell', c, self.sl, self.tp)
        
        return None


class MonthlyRotationStrategy:
    """Trade les effets de rotation mensuelle (end/start of month)."""
    
    def __init__(self):
        self.position = 0
        self.entry_price = 0
        self.sl = 0
        self.bars_since_entry = 0
        self.max_bars = 48
        self.prev_month = None
        self.prev_day = None
    
    def on_bar(self, ts_ms, o, h, l, c, v):
        dt = datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc)
        
        if self.position != 0:
            self.bars_since_entry += 1
            if self.bars_since_entry >= self.max_bars:
                self.position = 0
            if self.position == 1 and c <= self.sl:
                self.position = 0
            elif self.position == -1 and c >= self.sl:
                self.position = 0
            return None
        
        # Buy last 5 bars of month, first 5 of next month
        if self.prev_month is not None and self.prev_day is not None:
            days_in_month = [31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
            dim = days_in_month[dt.month - 1]
            
            is_end_month = (dim - dt.day) <= 2 and dt.day >= 25
            is_start_month = dt.day <= 3
            
            if is_end_month or is_start_month:
                self.position = 1
                self.entry_price = c
                self.sl = c * 0.97
                self.bars_since_entry = 0
                self.prev_month = dt.month
                self.prev_day = dt.day
                return ('buy', c, self.sl, None)
        
        self.prev_month = dt.month
        self.prev_day = dt.day
        return None


class NYMidSessionMomentumStrategy:
    """Suivi de momentum en milieu de session NY."""
    
    def __init__(self):
        self.position = 0
        self.entry_price = 0
        self.sl = 0
        self.tp = 0
        self.sma50 = RunningIndicators(50)
        self.atr = RunningATR(14)
        self.bars_since_entry = 0
        self.max_bars = 24
        self.session_high = 0
        self.session_low = float('inf')
        self.current_session = None  # 'asia', 'london', 'ny'
    
    def on_bar(self, ts_ms, o, h, l, c, v):
        self.sma50.add(c)
        self.atr.add(h, l, c)
        
        dt = datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc)
        hour_utc2 = (dt.hour + 2) % 24
        
        # Track session
        if 2 <= hour_utc2 < 8:
            session = 'asia'
        elif 8 <= hour_utc2 < 14:
            session = 'london'
        elif 14 <= hour_utc2 < 22:
            session = 'ny'
        else:
            session = 'asia'
        
        if session != self.current_session:
            self.session_high = 0
            self.session_low = float('inf')
            self.current_session = session
        
        self.session_high = max(self.session_high, h)
        self.session_low = min(self.session_low, l)
        
        if self.position != 0:
            self.bars_since_entry += 1
            if self.bars_since_entry >= self.max_bars:
                self.position = 0
            if self.position == 1 and c <= self.sl:
                self.position = 0
            elif self.position == -1 and c >= self.sl:
                self.position = 0
            return None
        
        if not self.atr.ready or not self.sma50.ready:
            return None
        
        # NY session: enter between 14:00-16:00 UTC+2
        if session == 'ny' and 14 <= hour_utc2 <= 16:
            atr_val = self.atr.value
            ny_range = self.session_high - self.session_low
            
            if ny_range > 0 and c > self.sma50.mean:
                self.position = 1
                self.entry_price = c
                self.sl = c - atr_val * 1.5
                self.tp = c + atr_val * 2.0
                self.bars_since_entry = 0
                return ('buy', c, self.sl, self.tp)
            elif ny_range > 0 and c < self.sma50.mean:
                self.position = -1
                self.entry_price = c
                self.sl = c + atr_val * 1.5
                self.tp = c - atr_val * 2.0
                self.bars_since_entry = 0
                return ('sell', c, self.sl, self.tp)
        
        return None


class SessionCloseReversalStrategy:
    """Reversal aux fermetures de sessions (London/NY)."""
    
    def __init__(self):
        self.position = 0
        self.entry_price = 0
        self.sl = 0
        self.tp = 0
        self.atr = RunningATR(14)
        self.rsi2 = RunningIndicators(2)  # 2-period RSI-like
        self.sma20 = RunningIndicators(20)
        self.bars_since_entry = 0
        self.max_bars = 24
        self.bar_count = 0
        self.prev_sessions = deque(maxlen=3)
    
    def on_bar(self, ts_ms, o, h, l, c, v):
        self.atr.add(h, l, c)
        self.sma20.add(c)
        self.bar_count += 1
        
        dt = datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc)
        hour_utc2 = (dt.hour + 2) % 24
        
        # Track session change
        if 8 <= hour_utc2 < 9:  # London opening
            self.prev_sessions.append(('london_open', c, o, h, l))
        elif 14 <= hour_utc2 < 15:  # NY opening
            self.prev_sessions.append(('ny_open', c, o, h, l))
        elif 20 <= hour_utc2 < 21:  # NY closing
            self.prev_sessions.append(('ny_close', c, o, h, l))
        
        if self.position != 0:
            self.bars_since_entry += 1
            if self.bars_since_entry >= self.max_bars:
                self.position = 0
            if self.position == 1 and c <= self.sl:
                self.position = 0
            elif self.position == -1 and c >= self.sl:
                self.position = 0
            return None
        
        if not self.atr.ready or not self.sma20.ready:
            return None
        
        atr_val = self.atr.value
        
        # Close of NY session: 20:00-21:00 UTC+2
        if 20 <= hour_utc2 <= 21:
            # Reversal signal: if bearish all day, go long at close
            if c < self.sma20.mean - atr_val:
                self.position = 1
                self.entry_price = c
                self.sl = c - atr_val
                self.tp = c + atr_val * 2
                self.bars_since_entry = 0
                return ('buy', c, self.sl, self.tp)
            elif c > self.sma20.mean + atr_val:
                self.position = -1
                self.entry_price = c
                self.sl = c + atr_val
                self.tp = c - atr_val * 2
                self.bars_since_entry = 0
                return ('sell', c, self.sl, self.tp)
        
        return None


class SessionTransitionStrategy:
    """Trade transitions entre sessions (Tokyo→London→NY)."""
    
    def __init__(self):
        self.position = 0
        self.entry_price = 0
        self.sl = 0
        self.tp = 0
        self.atr = RunningATR(14)
        self.bars_since_entry = 0
        self.max_bars = 12
        self.prev_open = None
    
    def on_bar(self, ts_ms, o, h, l, c, v):
        self.atr.add(h, l, c)
        
        dt = datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc)
        hour_utc2 = (dt.hour + 2) % 24
        
        if self.position != 0:
            self.bars_since_entry += 1
            if self.bars_since_entry >= self.max_bars:
                self.position = 0
            if self.position == 1 and c <= self.sl:
                self.position = 0
            elif self.position == -1 and c >= self.sl:
                self.position = 0
            return None
        
        if not self.atr.ready:
            return None
        
        atr_val = self.atr.value
        
        # London open transition (Tokyo→London overlap): 08:00-09:00 UTC+2
        if 8 <= hour_utc2 <= 9:
            if c > o + atr_val * 0.5:
                self.position = 1
                self.entry_price = c
                self.sl = c - atr_val * 1.5
                self.tp = c + atr_val * 2.0
                self.bars_since_entry = 0
                return ('buy', c, self.sl, self.tp)
        
        # NY open transition (London→NY): 14:00-15:00 UTC+2
        elif 14 <= hour_utc2 <= 15:
            if c < o - atr_val * 0.5:
                self.position = -1
                self.entry_price = c
                self.sl = c + atr_val * 1.5
                self.tp = c - atr_val * 2.0
                self.bars_since_entry = 0
                return ('sell', c, self.sl, self.tp)
        
        return None


class StreakReversalStrategy:
    """Reversal après X barres consécutives dans la même direction."""
    
    def __init__(self):
        self.position = 0
        self.entry_price = 0
        self.sl = 0
        self.tp = 0
        self.streak = 0
        self.streak_type = None  # 'up' or 'down'
        self.atr = RunningATR(10)
        self.bars_since_entry = 0
        self.max_bars = 24
    
    def on_bar(self, ts_ms, o, h, l, c, v):
        self.atr.add(h, l, c)
        
        # Track streak
        if c > o:
            if self.streak_type == 'up':
                self.streak += 1
            else:
                self.streak = 1
                self.streak_type = 'up'
        elif c < o:
            if self.streak_type == 'down':
                self.streak += 1
            else:
                self.streak = 1
                self.streak_type = 'down'
        else:
            self.streak = 0
            self.streak_type = None
        
        if self.position != 0:
            self.bars_since_entry += 1
            if self.bars_since_entry >= self.max_bars:
                self.position = 0
            if self.position == 1 and c <= self.sl:
                self.position = 0
            elif self.position == -1 and c >= self.sl:
                self.position = 0
            return None
        
        if not self.atr.ready:
            return None
        
        atr_val = self.atr.value
        
        # 4+ green bars → go short (reversal expected)
        if self.streak_type == 'up' and self.streak >= 4:
            self.position = -1
            self.entry_price = c
            self.sl = c + atr_val * 1.5
            self.tp = c - atr_val * 2
            self.bars_since_entry = 0
            return ('sell', c, self.sl, self.tp)
        
        # 4+ red bars → go long (reversal expected)
        elif self.streak_type == 'down' and self.streak >= 4:
            self.position = 1
            self.entry_price = c
            self.sl = c - atr_val * 1.5
            self.tp = c + atr_val * 2
            self.bars_since_entry = 0
            return ('buy', c, self.sl, self.tp)
        
        return None


class ThursdayRangeExpansionStrategy:
    """Trade l'expansion de range le jeudi (jour de forte volatilité)."""
    
    def __init__(self):
        self.position = 0
        self.entry_price = 0
        self.sl = 0
        self.tp = 0
        self.atr = RunningATR(20)
        self.bb = RunningBB(20, 2)
        self.bars_since_entry = 0
        self.max_bars = 48
        self.week_high = 0
        self.week_low = float('inf')
        self.last_thursday = -1
    
    def on_bar(self, ts_ms, o, h, l, c, v):
        self.atr.add(h, l, c)
        self.bb.add(c)
        
        dt = datetime.fromtimestamp(ts_ms / 1000, tz=timezone.utc)
        
        # Track weekly range
        self.week_high = max(self.week_high, h)
        self.week_low = min(self.week_low, l)
        
        # Reset at start of week
        if dt.weekday() == 0:
            self.week_high = h
            self.week_low = l
        
        if self.position != 0:
            self.bars_since_entry += 1
            if self.bars_since_entry >= self.max_bars:
                self.position = 0
            if self.position == 1 and c <= self.sl:
                self.position = 0
            elif self.position == -1 and c >= self.sl:
                self.position = 0
            return None
        
        if not self.atr.ready or not self.bb.ready:
            return None
        
        atr_val = self.atr.value
        weekly_range = self.week_high - self.week_low
        
        # Thursday: if price breaks above weekly mid-range with volatility
        if dt.weekday() == 3:
            mid_week = (self.week_high + self.week_low) / 2
            
            if c > mid_week and weekly_range > atr_val * 3:
                self.position = 1
                self.entry_price = c
                self.sl = self.week_low
                self.tp = c + weekly_range * 0.5
                self.bars_since_entry = 0
                return ('buy', c, self.sl, self.tp)
            elif c < mid_week and weekly_range > atr_val * 3:
                self.position = -1
                self.entry_price = c
                self.sl = self.week_high
                self.tp = c - weekly_range * 0.5
                self.bars_since_entry = 0
                return ('sell', c, self.sl, self.tp)
        
        return None


class VolClusterMomentumStrategy:
    """Momentum après cluster de forte volatilité."""
    
    def __init__(self):
        self.position = 0
        self.entry_price = 0
        self.sl = 0
        self.tp = 0
        self.atr_short = RunningATR(5)
        self.atr_long = RunningATR(20)
        self.sma20 = RunningIndicators(20)
        self.bars_since_entry = 0
        self.max_bars = 24
    
    def on_bar(self, ts_ms, o, h, l, c, v):
        self.atr_short.add(h, l, c)
        self.atr_long.add(h, l, c)
        self.sma20.add(c)
        
        if self.position != 0:
            self.bars_since_entry += 1
            if self.bars_since_entry >= self.max_bars:
                self.position = 0
            if self.position == 1 and c <= self.sl:
                self.position = 0
            elif self.position == -1 and c >= self.sl:
                self.position = 0
            return None
        
        if not self.atr_short.ready or not self.atr_long.ready or not self.sma20.ready:
            return None
        
        atr_ratio = self.atr_short.value / self.atr_long.value if self.atr_long.value > 0 else 0
        
        # Volatility cluster: short-term ATR > 1.5× long-term
        if atr_ratio > 1.5:
            if c > self.sma20.mean:
                self.position = 1
                self.entry_price = c
                self.sl = c - self.atr_short.value * 1.5
                self.tp = c + self.atr_short.value * 2
                self.bars_since_entry = 0
                return ('buy', c, self.sl, self.tp)
            elif c < self.sma20.mean:
                self.position = -1
                self.entry_price = c
                self.sl = c + self.atr_short.value * 1.5
                self.tp = c - self.atr_short.value * 2
                self.bars_since_entry = 0
                return ('sell', c, self.sl, self.tp)
        
        return None


class VolContractionBreakoutStrategy:
    """Breakout après contraction de la volatilité (Bollinger Squeeze)."""
    
    def __init__(self):
        self.position = 0
        self.entry_price = 0
        self.sl = 0
        self.tp = 0
        self.bb = RunningBB(20, 2)
        self.atr = RunningATR(14)
        self.bars_since_entry = 0
        self.max_bars = 24
        self.bb_prev = None
    
    def on_bar(self, ts_ms, o, h, l, c, v):
        self.bb.add(c)
        self.atr.add(h, l, c)
        
        if self.position != 0:
            self.bars_since_entry += 1
            if self.bars_since_entry >= self.max_bars:
                self.position = 0
            return None
        
        if not self.bb.ready or not self.atr.ready:
            self.bb_prev = (self.bb.upper, self.bb.lower, self.bb.middle) if self.bb.ready else None
            return None
        
        curr_bw = self.bb.bandwidth
        
        if self.bb_prev is not None:
            prev_bw = (self.bb_prev[0] - self.bb_prev[1]) / self.bb_prev[2] if self.bb_prev[2] != 0 else 0
            
            # Bandwidth contracted then expanding
            if curr_bw > prev_bw * 1.3 and curr_bw < 0.1:  # Squeeze + expansion start
                atr_val = self.atr.value
                if c > self.bb.middle:
                    self.position = 1
                    self.entry_price = c
                    self.sl = c - atr_val * 1.5
                    self.tp = c + atr_val * 2.5
                    self.bars_since_entry = 0
                    self.bb_prev = (self.bb.upper, self.bb.lower, self.bb.middle)
                    return ('buy', c, self.sl, self.tp)
                elif c < self.bb.middle:
                    self.position = -1
                    self.entry_price = c
                    self.sl = c + atr_val * 1.5
                    self.tp = c - atr_val * 2.5
                    self.bars_since_entry = 0
                    self.bb_prev = (self.bb.upper, self.bb.lower, self.bb.middle)
                    return ('sell', c, self.sl, self.tp)
        
        self.bb_prev = (self.bb.upper, self.bb.lower, self.bb.middle)
        return None


STRATEGIES = {
    'FridayBear': FridayBearStrategy,
    'GapFader': GapFaderStrategy,
    'LondonOpenVol': LondonOpenVolStrategy,
    'MonthlyRotation': MonthlyRotationStrategy,
    'NYMidSessionMomentum': NYMidSessionMomentumStrategy,
    'SessionCloseReversal': SessionCloseReversalStrategy,
    'SessionTransition': SessionTransitionStrategy,
    'StreakReversal': StreakReversalStrategy,
    'ThursdayRangeExpansion': ThursdayRangeExpansionStrategy,
    'VolClusterMomentum': VolClusterMomentumStrategy,
    'VolContractionBreakout': VolContractionBreakoutStrategy,
}


def backtest_single(bars, strategy_cls):
    """Backtest a single strategy on a list of bars."""
    strat = strategy_cls()
    result = BacktestResult()
    capital = CAPITAL
    
    pos = 0  # 0=none, 1=long, -1=short
    entry_price = 0
    sl = 0
    
    # Calculate position size (1% risk per trade)
    risk_per_trade = capital * 0.01
    
    for ts_ms, o, h, l, c, v in bars:
        signal = strat.on_bar(ts_ms, o, h, l, c, v)
        
        if signal is not None and pos == 0:
            direction, price, stop, _ = signal
            pos = 1 if direction == 'buy' else -1
            entry_price = price
            sl = stop
            result.trades += 1
        
        # Track equity
        if pos != 0:
            pnl = (c - entry_price) * (pos) / entry_price * risk_per_trade * 100
            result.max_equity = max(result.max_equity, capital + pnl)
        
        # Stop loss check (integrated in strategy, but also check here)
        if pos == 1 and c <= sl:
            exit_pnl = (sl - entry_price) / entry_price * risk_per_trade * 100
            if exit_pnl < 0:
                result.losses += 1
                result.gross_loss += abs(exit_pnl)
            else:
                result.wins += 1
                result.gross_profit += exit_pnl
            capital += exit_pnl
            pos = 0
        
        elif pos == -1 and c >= sl:
            exit_pnl = (entry_price - sl) / entry_price * risk_per_trade * 100
            if exit_pnl < 0:
                result.losses += 1
                result.gross_loss += abs(exit_pnl)
            else:
                result.wins += 1
                result.gross_profit += exit_pnl
            capital += exit_pnl
            pos = 0
        
        # Exit on strategy close
        if pos != 0 and strat.position == 0 and result.trades > 0:
            pnl = (c - entry_price) * (pos) / entry_price * risk_per_trade * 100
            if pnl > 0:
                result.wins += 1
                result.gross_profit += pnl
            else:
                result.losses += 1
                result.gross_loss += abs(pnl)
            capital += pnl
            pos = 0
        
        result.equity_curve.append(capital)
    
    metrics = calc_metrics(result)
    return metrics


def robustness_factor(metrics_list):
    """Calculate multi-asset robustness factor."""
    if not metrics_list:
        return -999
    
    rf = 0
    penalties = 0
    count = 0
    
    for m in metrics_list:
        if m['trades'] < 5:
            continue
        rf += m['sharpe']
        count += 1
        if m['max_dd'] > 40:
            rf -= 0.3
            penalties += 1
        if m['pf'] < 1.0 and m['trades'] > 0:
            rf -= 0.5
            penalties += 1
    
    if count == 0:
        return -999
    
    return round(rf / count, 2)


def run_all():
    """Run backtests for all strategies on all assets."""
    print("=" * 70)
    print("  MULTI-ASSET BACKTEST — 11 Strategies × 9 Assets")
    print("=" * 70)
    
    results = {}  # strategy_name -> {asset: metrics}
    
    for asset in ASSETS:
        print(f"\n📥 Loading {asset}...", end=" ")
        bars = read_bars(asset)
        print(f"{len(bars):,} barres")
        
        if len(bars) < 1000:
            continue
        
        # Reduce bars for speed: sample every 3rd for large datasets
        if len(bars) > 200000:
            step = len(bars) // 150000
            test_bars = [bars[i] for i in range(0, len(bars), step)]
            print(f"   Sampled to {len(test_bars):,} barres (step={step})")
        else:
            test_bars = bars
        
        for strat_name, strat_cls in STRATEGIES.items():
            metrics = backtest_single(test_bars, strat_cls)
            results.setdefault(strat_name, {})[asset] = metrics
    
    return results


def print_results(results):
    """Pretty-print results."""
    strat_names = list(results.keys())
    assets = list(next(iter(results.values())).keys())
    
    print("\n\n" + "=" * 70)
    print("  RESULTS MATRIX — Sharpe Ratio")
    print("=" * 70)
    
    # Header
    print(f"{'Strategy':25s}")
    for a in assets:
        print(f"{a[:8]:>8s}")
    print(f"{'RF':>8s}")
    print("-" * 70)
    
    rf_scores = {}
    for strat in strat_names:
        metrics_list = list(results[strat].values())
        rf = robustness_factor(metrics_list)
        rf_scores[strat] = rf
        
        print(f"{strat:25s}")
        for a in assets:
            m = results[strat].get(a, {})
            if m.get('trades', 0) >= 5:
                s = m['sharpe']
                color = '>' if s > 0.5 else ('=' if s > 0 else '<')
                print(f"{s:>7.1f} {color}")
            else:
                print(f"{'  -  ':>8s}")
        print(f"{rf:>8.2f}")
    
    print("\n\n" + "=" * 70)
    print("  TOP 3 — By Robustness Factor")
    print("=" * 70)
    
    for rank, (strat, rf) in enumerate(sorted(rf_scores.items(), key=lambda x: -x[1])[:3], 1):
        if rf <= -999:
            continue
        print(f"\n  🥇 #{rank}: {strat} (RF={rf})")
        for asset, m in results[strat].items():
            if m.get('trades', 0) >= 5:
                print(f"     {asset:10s}: Sharpe={m['sharpe']} Return={m['return_pct']}% WR={m['win_rate']}% PF={m['pf']} DD={m['max_dd']}% Trades={m['trades']}")
    
    print("\n\n" + "=" * 70)
    print("  TOP MONO-ASSET — Best Sharpe by Asset")
    print("=" * 70)
    
    for asset in assets:
        best = max(((s, results[s][asset]) for s in strat_names if results[s][asset].get('trades', 0) >= 5),
                   key=lambda x: x[1]['sharpe'], default=None)
        if best:
            print(f"  {asset:10s}: {best[0]} (Sharpe={best[1]['sharpe']} Return={best[1]['return_pct']}% DD={best[1]['max_dd']}%)")
    
    return rf_scores


def save_report(results, rf_scores):
    """Save full report."""
    os.makedirs(OUT_DIR, exist_ok=True)
    assets = list(next(iter(results.values())).keys())
    
    with open(os.path.join(OUT_DIR, "2026-05-21-report.md"), 'w') as f:
        f.write("# Multi-Asset Backtest Report — 2026-05-21\n\n")
        f.write(f"**Stratégies:** 11 Creative Lab | **Assets:** {len(assets)} H1 | **Capital:** $50,000\n\n")
        f.write("| Strategy |")
        for a in assets:
            f.write(f" {a} |")
        f.write(" RF |\n|---|")
        for _ in assets:
            f.write("---|")
        f.write("---:|\n")
        
        for strat in sorted(results.keys()):
            f.write(f"| {strat} |")
            for a in assets:
                m = results[strat].get(a, {})
                if m.get('trades', 0) >= 5:
                    f.write(f" S={m['sharpe']} |")
                else:
                    f.write(" - |")
            f.write(f" {rf_scores.get(strat, -999)} |\n")
        
        # Top 3
        f.write("\n## Top 3 — Robustness Factor\n\n")
        for rank, (strat, rf) in enumerate(sorted(rf_scores.items(), key=lambda x: -x[1])[:3], 1):
            if rf <= -999:
                continue
            f.write(f"### #{rank}: {strat} (RF={rf})\n\n")
            for asset, m in sorted(results[strat].items()):
                if m.get('trades', 0) >= 5:
                    f.write(f"- {asset}: Sharpe={m['sharpe']}, Return={m['return_pct']:.1f}%, PF={m['pf']}, DD={m['max_dd']}% (n={m['trades']})\n")
            f.write("\n")
        
        f.write("\n\n---\n*Generated by C-3PO Multi-Asset Backtester*\n")
    
    with open(os.path.join(OUT_DIR, "2026-05-21-recap.txt"), 'w') as f:
        f.write(f"🧪 **Creative Lab — Multi-Asset Backtest**\n\n")
        f.write(f"11 stratégies × {len(assets)} assets\n\n")
        
        for rank, (strat, rf) in enumerate(sorted(rf_scores.items(), key=lambda x: -x[1])[:3], 1):
            if rf <= -999:
                continue
            f.write(f"🏆 #{rank} **{strat}** (RF={rf})\n")
            for asset, m in sorted(results[strat].items()):
                if m.get('trades', 0) >= 5:
                    f.write(f"   {asset}: Sharpe={m['sharpe']}, +{m['return_pct']:.0f}%, DD={m['max_dd']}%\n")
            f.write("\n")
    
    print(f"\n📄 Report: {OUT_DIR}/2026-05-21-report.md")
    print(f"📄 Recap:  {OUT_DIR}/2026-05-21-recap.txt")


if __name__ == "__main__":
    results = run_all()
    rf_scores = print_results(results)
    save_report(results, rf_scores)
