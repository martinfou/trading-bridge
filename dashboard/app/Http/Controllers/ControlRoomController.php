<?php

namespace App\Http\Controllers;

use App\Services\ControlPlaneClient;
use Illuminate\Http\Client\RequestException;
use Illuminate\Http\RedirectResponse;
use Illuminate\Http\Request;
use Illuminate\View\View;

/** Prop-shop control room — polls Java control plane (Story 13.7). */
final class ControlRoomController extends Controller
{
    public function show(ControlPlaneClient $client): View
    {
        $error = null;
        $summary = ['runs' => [], 'signals' => ['gaps' => [], 'drift' => []], 'freshness' => []];
        $brokers = ['accounts' => []];
        $health = null;

        try {
            $health = $client->health();
            $summary = $client->controlSummary();
            $brokers = $client->brokerAccounts();
        } catch (RequestException $e) {
            $error = 'Control plane unreachable at '.$client->baseUrl().': '.$e->getMessage();
        }

        return view('control-room', [
            'error' => $error,
            'health' => $health,
            'summary' => $summary,
            'brokers' => $brokers,
            'controlPlaneUrl' => $client->baseUrl(),
            'refreshSeconds' => (int) config('trading.refresh_seconds', 5),
        ]);
    }

    public function kill(Request $request, string $strategyId, ControlPlaneClient $client): RedirectResponse
    {
        $validated = $request->validate([
            'reason' => ['nullable', 'string', 'max:500'],
        ]);

        try {
            $client->kill(
                $strategyId,
                'laravel-dashboard',
                $validated['reason'] ?? 'kill from control room UI',
            );
            return redirect()->route('control-room')
                ->with('status', "Kill accepted for {$strategyId}");
        } catch (RequestException $e) {
            return redirect()->route('control-room')
                ->with('error', "Kill failed: ".$e->getMessage());
        }
    }
}
