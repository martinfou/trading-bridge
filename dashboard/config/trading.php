<?php

return [

    /*
    |--------------------------------------------------------------------------
    | Java control plane (Story 13.7 — thin Laravel client)
    |--------------------------------------------------------------------------
    */

    'control_plane_url' => env('CONTROL_PLANE_URL', 'http://127.0.0.1:8080'),

    'refresh_seconds' => (int) env('CONTROL_ROOM_REFRESH_SECONDS', 5),

];
