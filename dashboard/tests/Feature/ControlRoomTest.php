<?php

namespace Tests\Feature;

use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class ControlRoomTest extends TestCase
{
    public function test_control_room_renders_summary_from_java_api(): void
    {
        config(['trading.control_plane_url' => 'http://control.test']);

        Http::fake([
            'http://control.test/api/health' => Http::response(['status' => 'ok', 'version' => '1.0.0']),
            'http://control.test/api/control/summary' => Http::response([
                'schemaVersion' => 1,
                'freshness' => ['lastEventAt' => '2024-01-01T00:00:00Z', 'secondsSinceLastEvent' => 10],
                'runs' => [[
                    'runId' => 'run-1',
                    'strategyId' => 'LondonOpenRangeBreakout',
                    'mode' => 'BACKTEST',
                    'executionLabel' => 'BACKTEST',
                    'status' => 'COMPLETED',
                    'isStale' => false,
                    'eventCount' => 5,
                ]],
                'signals' => ['gaps' => [], 'drift' => []],
            ]),
            'http://control.test/api/broker-accounts' => Http::response([
                'accounts' => [[
                    'id' => 'default',
                    'provider' => 'OANDA',
                    'accountIdMasked' => '****1234',
                    'credentialsConfigured' => false,
                ]],
            ]),
        ]);

        $response = $this->get('/control');

        $response->assertOk();
        $response->assertSee('LondonOpenRangeBreakout');
        $response->assertSee('Control Room');
    }

    public function test_kill_posts_to_control_plane(): void
    {
        config(['trading.control_plane_url' => 'http://control.test']);

        Http::fake([
            'http://control.test/api/strategies/LondonOpenRangeBreakout/kill' => Http::response([
                'killed' => true,
                'strategyId' => 'LondonOpenRangeBreakout',
            ], 202),
        ]);

        $response = $this->post('/control/strategies/LondonOpenRangeBreakout/kill', [
            'reason' => 'test kill',
        ]);

        $response->assertRedirect(route('control-room'));
        Http::assertSent(function ($request) {
            return $request->url() === 'http://control.test/api/strategies/LondonOpenRangeBreakout/kill'
                && $request['actor'] === 'laravel-dashboard';
        });
    }
}
