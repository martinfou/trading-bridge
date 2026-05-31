<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta http-equiv="refresh" content="{{ $refreshSeconds }}">
    <title>Trading Bridge — Control Room</title>
    <style>
        :root { font-family: system-ui, sans-serif; color: #1a1a1a; background: #f4f6f8; }
        body { margin: 0; padding: 1.25rem 1.5rem; }
        h1 { margin: 0 0 0.25rem; font-size: 1.35rem; }
        .meta { color: #555; font-size: 0.9rem; margin-bottom: 1rem; }
        .banner { padding: 0.75rem 1rem; border-radius: 6px; margin-bottom: 1rem; }
        .banner.error { background: #fde8e8; color: #9b1c1c; }
        .banner.ok { background: #e6f4ea; color: #1e6b3a; }
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 1rem; margin-bottom: 1.25rem; }
        .card { background: #fff; border: 1px solid #dde3ea; border-radius: 8px; padding: 1rem; }
        .card h2 { margin: 0 0 0.75rem; font-size: 1rem; }
        table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
        th, td { text-align: left; padding: 0.45rem 0.5rem; border-bottom: 1px solid #eef1f4; vertical-align: top; }
        th { color: #666; font-weight: 600; }
        .badge { display: inline-block; padding: 0.15rem 0.45rem; border-radius: 4px; font-size: 0.75rem; font-weight: 600; }
        .badge.stale { background: #fff3cd; color: #856404; }
        .badge.ok { background: #d4edda; color: #155724; }
        .badge.label { background: #e8eaf6; color: #283593; }
        .signals li { margin-bottom: 0.35rem; font-size: 0.85rem; }
        form.kill { display: inline; }
        button.kill { background: #c62828; color: #fff; border: none; padding: 0.25rem 0.5rem; border-radius: 4px; cursor: pointer; font-size: 0.75rem; }
        button.kill:hover { background: #b71c1c; }
    </style>
</head>
<body>
    <h1>Control Room</h1>
    <p class="meta">
        Java control plane: <code>{{ $controlPlaneUrl }}</code>
        · auto-refresh {{ $refreshSeconds }}s
        @if($health)
            · engine {{ $health['version'] ?? '?' }}
        @endif
    </p>

    @if(session('status'))
        <div class="banner ok">{{ session('status') }}</div>
    @endif
    @if(session('error'))
        <div class="banner error">{{ session('error') }}</div>
    @endif
    @if($error)
        <div class="banner error">{{ $error }}</div>
    @endif

    <div class="grid">
        <div class="card">
            <h2>Freshness</h2>
            @php $f = $summary['freshness'] ?? []; @endphp
            <p>Last event: {{ $f['lastEventAt'] ?? '—' }}</p>
            <p>Seconds ago: {{ $f['secondsSinceLastEvent'] ?? '—' }}</p>
        </div>
        <div class="card">
            <h2>Broker accounts</h2>
            <ul class="signals">
                @forelse($brokers['accounts'] ?? [] as $account)
                    <li>
                        <strong>{{ $account['id'] }}</strong>
                        {{ $account['provider'] }}
                        {{ ($account['credentialsConfigured'] ?? false) ? '✓ configured' : '✗ not configured' }}
                        <span class="meta">({{ $account['accountIdMasked'] ?? '****' }})</span>
                    </li>
                @empty
                    <li>No broker accounts</li>
                @endforelse
            </ul>
        </div>
    </div>

    <div class="card" style="margin-bottom: 1rem;">
        <h2>Runs — exposure &amp; daily drawdown</h2>
        <table>
            <thead>
                <tr>
                    <th>Strategy</th>
                    <th>Mode / label</th>
                    <th>Status</th>
                    <th>Daily DD</th>
                    <th>Events</th>
                    <th></th>
                </tr>
            </thead>
            <tbody>
                @forelse($summary['runs'] ?? [] as $run)
                    <tr>
                        <td>{{ $run['strategyId'] }}<br><small>{{ $run['runId'] }}</small></td>
                        <td>
                            {{ $run['mode'] ?? '—' }}
                            <span class="badge label">{{ $run['executionLabel'] ?? '—' }}</span>
                        </td>
                        <td>
                            {{ $run['status'] ?? '—' }}
                            @if($run['isStale'] ?? false)
                                <span class="badge stale">STALE</span>
                            @else
                                <span class="badge ok">LIVE</span>
                            @endif
                        </td>
                        <td>
                            @if(isset($run['dailyDrawdownPct']))
                                {{ number_format($run['dailyDrawdownPct'], 2) }}%
                                @if($run['dailyDdBreached'] ?? false)
                                    <span class="badge stale">BREACH</span>
                                @endif
                            @else
                                —
                            @endif
                        </td>
                        <td>{{ $run['eventCount'] ?? 0 }}</td>
                        <td>
                            <form class="kill" method="post" action="{{ route('control-room.kill', $run['strategyId']) }}"
                                  onsubmit="return confirm('Kill {{ $run['strategyId'] }}?');">
                                @csrf
                                <input type="hidden" name="reason" value="dashboard kill">
                                <button class="kill" type="submit">Kill</button>
                            </form>
                        </td>
                    </tr>
                @empty
                    <tr><td colspan="6">No runs</td></tr>
                @endforelse
            </tbody>
        </table>
    </div>

    <div class="grid">
        <div class="card">
            <h2>Gap signals</h2>
            <ul class="signals">
                @forelse($summary['signals']['gaps'] ?? [] as $signal)
                    <li>{{ $signal['strategyId'] }} ({{ $signal['runId'] }}) — {{ count($signal['gaps'] ?? []) }} gap(s)</li>
                @empty
                    <li>No sequence gaps</li>
                @endforelse
            </ul>
        </div>
        <div class="card">
            <h2>Drift signals</h2>
            <ul class="signals">
                @forelse($summary['signals']['drift'] ?? [] as $drift)
                    <li>
                        <strong>{{ $drift['strategyId'] ?? '?' }}</strong>
                        {{ $drift['recommendation'] ?? 'HOLD' }}
                        <small>{{ $drift['reason'] ?? '' }}</small>
                    </li>
                @empty
                    <li>No drift signals (or insufficient broker history)</li>
                @endforelse
            </ul>
        </div>
    </div>
</body>
</html>
