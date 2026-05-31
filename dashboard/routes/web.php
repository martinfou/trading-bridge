<?php

use App\Http\Controllers\ControlRoomController;
use Illuminate\Support\Facades\Route;

Route::redirect('/', '/control');

Route::get('/control', [ControlRoomController::class, 'show'])->name('control-room');
Route::post('/control/strategies/{strategyId}/kill', [ControlRoomController::class, 'kill'])
    ->name('control-room.kill');
