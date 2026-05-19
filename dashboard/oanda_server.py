import json, urllib.request, http.server, socketserver, time, os, threading

script_dir = os.path.dirname(os.path.abspath(__file__))
creds_path = os.path.join(script_dir, 'oanda_creds.json')
if not os.path.exists(creds_path):
    # Fallback: use Laravel .env format
    import subprocess
    env_path = os.path.expanduser('~/projects/trading-dashboard/.env')
    if os.path.exists(env_path):
        with open(env_path) as f:
            for line in f:
                if line.startswith('OANDA_API_KEY'):
                    api_key = line.split('=')[1].strip()
                if line.startswith('OANDA_ACCOUNT_ID'):
                    account_id = line.split('=')[1].strip()
    else:
        api_key, account_id = None, None
else:
    with open(creds_path) as f:
        c = json.load(f)
        api_key = c['api_key']
        account_id = c['account_id']

if not api_key:
    print("❌ No OANDA credentials found")
    exit(1)

BASE_URL = "https://api-fxpractice.oanda.com/v3/"
AUTH = "Bearer " + api_key

SYMBOLS = {
    'EUR_USD': 'EUR/USD', 'GBP_USD': 'GBP/USD', 'USD_CAD': 'USD/CAD',
    'AUD_USD': 'AUD/USD', 'GBP_JPY': 'GBP/JPY', 'USD_CHF': 'USD/CHF',
    'NZD_USD': 'NZD/USD'
}

cache = {'prices': {}, 'account': {}, 'candles': {}, 'timestamp': 0}
cache_lock = threading.Lock()

def do_req(url):
    req = urllib.request.Request(url, headers={'Authorization': AUTH})
    with urllib.request.urlopen(req, timeout=5) as r:
        return json.loads(r.read())

def fetch_prices():
    data = do_req(f"{BASE_URL}accounts/{account_id}/pricing?instruments={','.join(SYMBOLS.keys())}")
    prices = {}
    for p in data.get('prices', []):
        prices[p['instrument']] = {
            'bid': float(p['bids'][0]['price']),
            'ask': float(p['asks'][0]['price']),
            'spread': round(float(p['asks'][0]['price']) - float(p['bids'][0]['price']), 5)
        }
    return prices

def fetch_account():
    data = do_req(f"{BASE_URL}accounts/{account_id}")
    a = data.get('account', {})
    return {
        'balance': float(a.get('balance', 0)),
        'nav': float(a.get('NAV', a.get('balance', 0))),
        'pl': float(a.get('unrealizedPl', 0)),
        'trades': len(a.get('openTrades', []))
    }

def refresh_cache():
    global cache
    while True:
        try:
            prices = fetch_prices()
            account = fetch_account()
            with cache_lock:
                cache['prices'] = prices
                cache['account'] = account
                cache['timestamp'] = time.time()
            print(f"  ↻ Updated {len(prices)} pairs | Bal: ${account['balance']:,.2f}")
        except Exception as e:
            print(f"  ⚠ Refresh error: {e}")
        time.sleep(10)

class SSEHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/api/stream':
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Cache-Control', 'no-cache')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.send_header('Connection', 'keep-alive')
            self.end_headers()
            last_ts = 0
            while True:
                with cache_lock:
                    if cache['timestamp'] > last_ts:
                        payload = json.dumps({'prices': cache['prices'], 'account': cache['account']})
                        self.wfile.write(f"data: {payload}\n\n".encode())
                        self.wfile.flush()
                        last_ts = cache['timestamp']
                time.sleep(1)
        
        elif self.path == '/api/data':
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Access-Control-Allow-Origin', '*')
            self.end_headers()
            with cache_lock:
                self.wfile.write(json.dumps(cache).encode())
        
        else:
            super().do_GET()
    
    def log_message(self, *a): pass

if __name__ == '__main__':
    # Initial fetch
    print("🚀 OANDA Live Streaming Server")
    try:
        prices = fetch_prices()
        account = fetch_account()
        with cache_lock:
            cache['prices'] = prices
            cache['account'] = account
            cache['timestamp'] = time.time()
        print(f"   Balance: ${account.get('balance',0):,.2f}")
        for k, v in prices.items():
            print(f"   {SYMBOLS.get(k,k)}: {v['bid']}")
    except Exception as e:
        print(f"❌ API Error: {e}")
        exit(1)
    
    # Background refresh thread
    t = threading.Thread(target=refresh_cache, daemon=True)
    t.start()
    
    port = 8082
    server = socketserver.TCPServer(("0.0.0.0", port), SSEHandler)
    print(f"📊 Dashboard: http://localhost:{port}/dashboard.html")
    print(f"📡 SSE stream: http://localhost:{port}/api/stream")
    print(f"↻ Auto-refresh every 10s")
    server.serve_forever()
