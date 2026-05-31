<?php

namespace App\Services;

use Illuminate\Http\Client\RequestException;
use Illuminate\Support\Facades\Http;
final class ControlPlaneClient
{
    public function __construct(
        private readonly string $baseUrl,
    ) {}

    public static function fromConfig(): self
    {
        $url = rtrim((string) config('trading.control_plane_url'), '/');

        return new self($url);
    }

    /** @return array<string, mixed> */
    public function health(): array
    {
        return $this->get('/api/health');
    }

    /** @return array<string, mixed> */
    public function controlSummary(): array
    {
        return $this->get('/api/control/summary');
    }

    /** @return array<string, mixed> */
    public function brokerAccounts(): array
    {
        return $this->get('/api/broker-accounts');
    }

    /** @return array<string, mixed> */
    public function kill(string $strategyId, string $actor, string $reason): array
    {
        return $this->post("/api/strategies/{$strategyId}/kill", [
            'actor' => $actor,
            'reason' => $reason,
        ]);
    }

    public function baseUrl(): string
    {
        return $this->baseUrl;
    }

    /** @return array<string, mixed> */
    private function get(string $path): array
    {
        $response = Http::timeout(15)
            ->acceptJson()
            ->get($this->baseUrl.$path);

        $response->throw();

        return $response->json() ?? [];
    }

    /** @return array<string, mixed> */
    private function post(string $path, array $body): array
    {
        $response = Http::timeout(30)
            ->acceptJson()
            ->post($this->baseUrl.$path, $body);

        $response->throw();

        return $response->json() ?? [];
    }
}
