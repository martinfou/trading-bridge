import json, urllib.request, http.server, socketserver, time, os

# Read credentials from file
script_dir = os.path.dirname(os.path.abspath(__file__))
with open(os.path.join(script_dir, 'oanda_creds.json')) as f:
    creds = json.load(f)

API_KEY = creds['api_key']
BASE_URL = "https://api-fxpractice.oanda.com/v3/"
ACCOUNT_ID = creds['account_id']
AUTH = "B" + "earer " + API_KEY

SYMBOLS = {'EUR_USD': 'EUR/USD', 'GBP_USD': 'GBP/USD','USD_CAD': 'USD/CAD',
           'AUD_USD': 'AUD/USD','GBP_JPY': 'GBP/JPY', 'USD_CHF': 'USD/CHF'}

def do_req(url):
    req = urllib.request.Request(url, headers={'Authorization': AUTH})
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.loads(r.read())

def fetch_candles(symbol, count=120):
    try:
        data = do_req(f"{BASE_URL}instruments/{symbol}/candles?granularity=H1&count={count}")
        candles = []
        for c in data.get('candles', []):
            if not c.get('complete'): continue
            m = c.get('mid', {})
            candles.append({'time': c['time'][:19], 'open': float(m['o']),
                'high': float(m['h']), 'low': float(m['l']),
                'close': float(m['c']), 'volume': c.get('volume', 0)})
        return candles
    except: return []

def fetch_prices():
    try:
        syms = ','.join(SYMBOLS.keys())
        data = do_req(f"{BASE_URL}accounts/{ACCOUNT_ID}/pricing?instruments={syms}")
        return {p['instrument']: {
            'bid': float(p['bids'][0]['price']), 'ask': float(p['asks'][0]['price']),
            'spread': round(float(p['asks'][0]['price'])-float(p['bids'][0]['price']),5)
        } for p in data.get('prices', [])}
    except: return {}

def fetch_account():
    try:
        data = do_req(f"{BASE_URL}accounts/{ACCOUNT_ID}/summary")['account']
        return {'balance': float(data['balance']), 'pl': float(data.get('unrealizedPL',0)),
                'trades': data.get('openTradeCount',0)}
    except: return {}

cache = {'prices': {}, 'account': {}, 'candles': {}, 'time': 0}

def get_data():
    if time.time() - cache['time'] < 10 and cache['candles']:
        return cache
    cache['prices'] = fetch_prices()
    cache['account'] = fetch_account()
    for sym in SYMBOLS:
        cache['candles'][sym] = fetch_candles(sym, 120)
    cache['time'] = time.time()
    return cache

class Handler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/api/data':
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            self.wfile.write(json.dumps(get_data()).encode())
        else:
            self.path = '/' if self.path == '/api/data' else self.path
            super().do_GET()
    def log_message(self, *a): pass

if __name__ == '__main__':
    # Verify API works
    try:
        acct = fetch_account()
        print(f"   Balance: ${acct.get('balance','?')}")
        prices = fetch_prices()
        for k,v in prices.items():
            print(f"   {SYMBOLS.get(k,k)}: {v['bid']}")
    except Exception as e:
        print(f"API Error: {e}")
    
    port = 8081
    server = socketserver.TCPServer(("0.0.0.0", port), Handler)
    print(f"🚀 Dashboard: http://localhost:{port}")
    server.serve_forever()
