#!/usr/bin/env python3
"""
Update Hermes Web with Discovered Strategy Backtest Data
--------------------------------------------------------
Updates:
  1. app/Http/Controllers/AlgoTradingController.php
  2. resources/js/Pages/Trading/StrategyCreation.vue
  3. resources/js/Pages/Trading/Backtesting.vue
"""

from pathlib import Path

HERMES_ROOT = Path("/Volumes/T7/src/hermes-web")


def update_algo_controller():
    controller_path = HERMES_ROOT / "app/Http/Controllers/AlgoTradingController.php"
    content = controller_path.read_text(encoding="utf-8")

    # Add Cache import if not present
    if "use Illuminate\\Support\\Facades\\Cache;" not in content:
        content = content.replace(
            "use Illuminate\\Support\\Facades\\Log;",
            "use Illuminate\\Support\\Facades\\Cache;\nuse Illuminate\\Support\\Facades\\Log;"
        )

    # Update index method to accept Request and pass candidates
    old_index = """    public function index(): Response
    {
        $results = BacktestResult::query()
            ->where('user_id', auth()->id())
            ->orderBy('backtest_date', 'desc')
            ->orderBy('sharpe_ratio', 'desc')
            ->get()
            ->map(fn (BacktestResult $r) => [
                'id' => $r->id,
                'title' => $r->title,
                'strategy_name' => $r->strategy_name,
                'asset' => $r->asset,
                'sharpe_ratio' => (float) $r->sharpe_ratio,
                'profit_factor' => (float) $r->profit_factor,
                'win_rate' => (float) $r->win_rate,
                'max_drawdown' => (float) $r->max_drawdown,
                'total_trades' => $r->total_trades,
                'total_return_pct' => (float) $r->total_return_pct,
                'qualified' => $r->qualified,
                'pdf_url' => $r->pdfUrl(),
                'download_url' => route('trading.backtesting.download', $r->id),
                'notes' => $r->notes,
                'backtest_date' => $r->backtest_date?->format('Y-m-d'),
                'created_at' => $r->created_at->format('Y-m-d H:i'),
            ]);

        return Inertia::render('Trading/Backtesting', [
            'results' => $results,
        ]);
    }"""

    new_index = """    public function index(Request $request): Response
    {
        $candidateQuery = $request->query('candidate');

        $results = BacktestResult::query()
            ->where('user_id', auth()->id())
            ->orderBy('backtest_date', 'desc')
            ->orderBy('sharpe_ratio', 'desc')
            ->get()
            ->map(fn (BacktestResult $r) => [
                'id' => $r->id,
                'title' => $r->title,
                'strategy_name' => $r->strategy_name,
                'asset' => $r->asset,
                'sharpe_ratio' => (float) $r->sharpe_ratio,
                'profit_factor' => (float) $r->profit_factor,
                'win_rate' => (float) $r->win_rate,
                'max_drawdown' => (float) $r->max_drawdown,
                'total_trades' => $r->total_trades,
                'total_return_pct' => (float) $r->total_return_pct,
                'qualified' => $r->qualified,
                'pdf_url' => $r->pdfUrl(),
                'download_url' => route('trading.backtesting.download', $r->id),
                'notes' => $r->notes,
                'backtest_date' => $r->backtest_date?->format('Y-m-d'),
                'created_at' => $r->created_at->format('Y-m-d H:i'),
            ]);

        $cached = Cache::get('sqx_live_telemetry');
        $candidates = $cached['candidates'] ?? [];

        return Inertia::render('Trading/Backtesting', [
            'results' => $results,
            'candidates' => $candidates,
            'selectedCandidate' => $candidateQuery,
        ]);
    }"""

    if old_index in content:
        content = content.replace(old_index, new_index)
        controller_path.write_text(content, encoding="utf-8")
        print("Updated AlgoTradingController.php successfully.")
    else:
        print("AlgoTradingController.php index method already updated or pattern mismatch.")


def update_strategy_creation_vue():
    path = HERMES_ROOT / "resources/js/Pages/Trading/StrategyCreation.vue"
    content = path.read_text(encoding="utf-8")

    # Update Candidate interface
    old_cand_interface = """interface Candidate {
    filename: string;
    strategy_name: string;
    filesize_kb: number;
    created_at: string;
    status: string;
    asset: string;
    side: string;
}"""

    new_cand_interface = """interface Candidate {
    filename: string;
    strategy_name: string;
    filesize_kb: number;
    created_at: string;
    status: string;
    asset: string;
    side: string;
    net_profit?: number;
    profit_factor?: number;
    win_rate?: number;
    trades?: number;
    wins?: number;
    losses?: number;
    max_drawdown?: number;
    max_drawdown_pct?: number;
    ret_dd_ratio?: number;
    sharpe_ratio?: number;
    is_trades?: number;
    is_profit?: number;
    is_pf?: number;
    oos_trades?: number;
    oos_profit?: number;
    oos_pf?: number;
    rules_summary?: string;
    sparkline?: number[];
}"""

    if old_cand_interface in content:
        content = content.replace(old_cand_interface, new_cand_interface)

    # Add modal state and imports
    if "const selectedCandidate = ref<Candidate | null>(null);" not in content:
        # Check imports for X icon
        if "X," not in content and ", X " not in content and " X " not in content:
            content = content.replace(
                "import {\n    Activity,",
                "import {\n    Activity,\n    X,"
            )

        modal_state_code = """const selectedCandidate = ref<Candidate | null>(null);
const showCandidateModal = ref(false);

function inspectCandidate(c: Candidate) {
    selectedCandidate.value = c;
    showCandidateModal.value = true;
}

function closeCandidateModal() {
    showCandidateModal.value = false;
    selectedCandidate.value = null;
}
"""
        content = content.replace("const isFetching = ref(false);", "const isFetching = ref(false);\n" + modal_state_code)

    # Replace Candidate Table
    old_table_section = """                <div v-else class="overflow-x-auto">
                    <table class="w-full text-left text-xs">
                        <thead>
                            <tr class="border-b border-border text-ink-muted">
                                <th class="pb-2 font-medium">Strategy</th>
                                <th class="pb-2 font-medium">Asset</th>
                                <th class="pb-2 font-medium">Direction</th>
                                <th class="pb-2 font-medium">Size</th>
                                <th class="pb-2 font-medium">Discovered</th>
                                <th class="pb-2 font-medium">Status</th>
                                <th class="pb-2 font-medium text-right">Actions</th>
                            </tr>
                        </thead>
                        <tbody class="divide-y divide-border">
                            <tr v-for="c in status.candidates" :key="c.filename" class="hover:bg-surface-50/50 transition">
                                <td class="py-3 font-bold text-ink font-mono">{{ c.strategy_name }}</td>
                                <td class="py-3 text-ink">{{ c.asset }}</td>
                                <td class="py-3">
                                    <span class="px-2 py-0.5 rounded text-[10px] font-semibold"
                                          :class="c.side === 'LONG' ? 'bg-emerald-500/10 text-emerald-400' : 'bg-red-500/10 text-red-400'">
                                        {{ c.side }}
                                    </span>
                                </td>
                                <td class="py-3 font-mono text-ink-muted">{{ c.filesize_kb }} KB</td>
                                <td class="py-3 text-ink-muted">{{ c.created_at }}</td>
                                <td class="py-3">
                                    <span class="px-2 py-0.5 rounded-full text-[10px] font-semibold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                                        {{ c.status }}
                                    </span>
                                </td>
                                <td class="py-3 text-right">
                                    <a :href="'/trading/backtesting?candidate=' + c.strategy_name"
                                       class="px-2.5 py-1 rounded-lg bg-accent-200/10 text-accent-200 hover:bg-accent-200/20 transition text-[11px] font-semibold inline-flex items-center gap-1">
                                        Validate in Hermes
                                        <ArrowRight class="w-3 h-3" />
                                    </a>
                                </td>
                            </tr>
                        </tbody>
                    </table>
                </div>"""

    new_table_section = """                <div v-else class="overflow-x-auto">
                    <table class="w-full text-left text-xs">
                        <thead>
                            <tr class="border-b border-border text-ink-muted">
                                <th class="pb-2 font-medium">Strategy</th>
                                <th class="pb-2 font-medium">Asset</th>
                                <th class="pb-2 font-medium">Side</th>
                                <th class="pb-2 font-medium text-right">Net Profit</th>
                                <th class="pb-2 font-medium text-right">PF</th>
                                <th class="pb-2 font-medium text-right">Win Rate</th>
                                <th class="pb-2 font-medium text-right">Max DD</th>
                                <th class="pb-2 font-medium text-right">Trades</th>
                                <th class="pb-2 font-medium text-right">Ret/DD</th>
                                <th class="pb-2 font-medium text-right">OOS PF</th>
                                <th class="pb-2 font-medium text-center">Status</th>
                                <th class="pb-2 font-medium text-right">Actions</th>
                            </tr>
                        </thead>
                        <tbody class="divide-y divide-border">
                            <tr v-for="c in status.candidates" :key="c.filename" class="hover:bg-surface-50/50 transition">
                                <td class="py-3 font-bold text-ink font-mono">{{ c.strategy_name }}</td>
                                <td class="py-3 text-ink">{{ c.asset }}</td>
                                <td class="py-3">
                                    <span class="px-2 py-0.5 rounded text-[10px] font-semibold"
                                          :class="c.side === 'LONG' ? 'bg-emerald-500/10 text-emerald-400' : 'bg-red-500/10 text-red-400'">
                                        {{ c.side }}
                                    </span>
                                </td>
                                <td class="py-3 text-right font-mono font-semibold"
                                    :class="(c.net_profit ?? 0) >= 0 ? 'text-emerald-400' : 'text-red-400'">
                                    {{ (c.net_profit ?? 0) >= 0 ? '+' : '' }}${{ (c.net_profit ?? 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}
                                </td>
                                <td class="py-3 text-right font-mono font-semibold">
                                    <span class="px-2 py-0.5 rounded text-[11px]"
                                          :class="(c.profit_factor ?? 1) >= 1.25 ? 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/30 font-bold' : ((c.profit_factor ?? 1) >= 1.05 ? 'bg-accent-200/15 text-accent-200' : 'text-ink-muted')">
                                        {{ (c.profit_factor ?? 1).toFixed(2) }}
                                    </span>
                                </td>
                                <td class="py-3 text-right font-mono text-ink">
                                    {{ (c.win_rate ?? 0).toFixed(1) }}%
                                </td>
                                <td class="py-3 text-right font-mono text-red-400/80">
                                    ${{ (c.max_drawdown ?? 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}
                                </td>
                                <td class="py-3 text-right font-mono text-ink">
                                    {{ c.trades ?? 0 }}
                                </td>
                                <td class="py-3 text-right font-mono font-semibold"
                                    :class="(c.ret_dd_ratio ?? 0) >= 2.0 ? 'text-emerald-400' : 'text-ink'">
                                    {{ (c.ret_dd_ratio ?? 0).toFixed(2) }}
                                </td>
                                <td class="py-3 text-right font-mono font-semibold">
                                    <span :class="(c.oos_pf ?? 1) >= 1.3 ? 'text-emerald-400 font-bold' : ((c.oos_pf ?? 1) >= 1.05 ? 'text-accent-200' : 'text-red-400')">
                                        {{ (c.oos_pf ?? 1).toFixed(2) }}
                                    </span>
                                </td>
                                <td class="py-3 text-center">
                                    <span class="px-2 py-0.5 rounded-full text-[10px] font-semibold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                                        {{ c.status }}
                                    </span>
                                </td>
                                <td class="py-3 text-right space-x-2 whitespace-nowrap">
                                    <button @click="inspectCandidate(c)"
                                            class="px-2.5 py-1 rounded-lg bg-surface-50 hover:bg-surface-100 text-ink text-[11px] font-semibold border border-border inline-flex items-center gap-1 transition">
                                        Inspect
                                    </button>
                                    <a :href="'/trading/backtesting?candidate=' + encodeURIComponent(c.strategy_name)"
                                       class="px-2.5 py-1 rounded-lg bg-accent-200/10 text-accent-200 hover:bg-accent-200/20 transition text-[11px] font-semibold inline-flex items-center gap-1">
                                        Validate
                                        <ArrowRight class="w-3 h-3" />
                                    </a>
                                </td>
                            </tr>
                        </tbody>
                    </table>
                </div>"""

    if old_table_section in content:
        content = content.replace(old_table_section, new_table_section)

    # Add modal component markup at the end of the template (before </AuthenticatedLayout>)
    modal_markup = """
            <!-- Candidate Detail Modal -->
            <div v-if="showCandidateModal && selectedCandidate"
                 class="fixed inset-0 z-50 bg-black/60 backdrop-blur-sm flex items-center justify-center p-4">
                <div class="bg-surface border border-border rounded-2xl max-w-2xl w-full p-6 space-y-6 shadow-2xl overflow-y-auto max-h-[90vh]">
                    <div class="flex items-center justify-between border-b border-border pb-4">
                        <div class="space-y-1">
                            <div class="flex items-center gap-2">
                                <h3 class="text-lg font-bold text-ink font-mono">{{ selectedCandidate.strategy_name }}</h3>
                                <span class="px-2 py-0.5 rounded text-xs font-semibold"
                                      :class="selectedCandidate.side === 'LONG' ? 'bg-emerald-500/10 text-emerald-400' : 'bg-red-500/10 text-red-400'">
                                    {{ selectedCandidate.side }}
                                </span>
                                <span class="px-2 py-0.5 rounded-full text-xs font-semibold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                                    {{ selectedCandidate.status }}
                                </span>
                            </div>
                            <p class="text-xs text-ink-muted">Asset: {{ selectedCandidate.asset }} | Discovered: {{ selectedCandidate.created_at }}</p>
                        </div>
                        <button @click="closeCandidateModal" class="p-1.5 rounded-lg hover:bg-surface-50 text-ink-muted hover:text-ink">
                            <X class="w-5 h-5" />
                        </button>
                    </div>

                    <!-- Metrics Grid -->
                    <div class="grid grid-cols-2 sm:grid-cols-4 gap-3">
                        <div class="p-3 rounded-xl bg-surface-50/50 border border-border space-y-1">
                            <div class="text-[11px] text-ink-muted uppercase tracking-wider font-semibold">Net Profit</div>
                            <div class="text-lg font-mono font-bold"
                                 :class="(selectedCandidate.net_profit ?? 0) >= 0 ? 'text-emerald-400' : 'text-red-400'">
                                {{ (selectedCandidate.net_profit ?? 0) >= 0 ? '+' : '' }}${{ (selectedCandidate.net_profit ?? 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}
                            </div>
                        </div>
                        <div class="p-3 rounded-xl bg-surface-50/50 border border-border space-y-1">
                            <div class="text-[11px] text-ink-muted uppercase tracking-wider font-semibold">Profit Factor</div>
                            <div class="text-lg font-mono font-bold text-accent-200">
                                {{ (selectedCandidate.profit_factor ?? 1).toFixed(2) }}
                            </div>
                        </div>
                        <div class="p-3 rounded-xl bg-surface-50/50 border border-border space-y-1">
                            <div class="text-[11px] text-ink-muted uppercase tracking-wider font-semibold">Win Rate</div>
                            <div class="text-lg font-mono font-bold text-ink">
                                {{ (selectedCandidate.win_rate ?? 0).toFixed(1) }}%
                            </div>
                            <div class="text-[10px] text-ink-muted">{{ selectedCandidate.wins ?? 0 }}W / {{ selectedCandidate.losses ?? 0 }}L</div>
                        </div>
                        <div class="p-3 rounded-xl bg-surface-50/50 border border-border space-y-1">
                            <div class="text-[11px] text-ink-muted uppercase tracking-wider font-semibold">Total Trades</div>
                            <div class="text-lg font-mono font-bold text-ink">
                                {{ selectedCandidate.trades ?? 0 }}
                            </div>
                        </div>
                        <div class="p-3 rounded-xl bg-surface-50/50 border border-border space-y-1">
                            <div class="text-[11px] text-ink-muted uppercase tracking-wider font-semibold">Max Drawdown</div>
                            <div class="text-lg font-mono font-bold text-red-400">
                                ${{ (selectedCandidate.max_drawdown ?? 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}
                            </div>
                            <div class="text-[10px] text-ink-muted">{{ (selectedCandidate.max_drawdown_pct ?? 0).toFixed(1) }}% equity</div>
                        </div>
                        <div class="p-3 rounded-xl bg-surface-50/50 border border-border space-y-1">
                            <div class="text-[11px] text-ink-muted uppercase tracking-wider font-semibold">Return / DD</div>
                            <div class="text-lg font-mono font-bold text-ink">
                                {{ (selectedCandidate.ret_dd_ratio ?? 0).toFixed(2) }}
                            </div>
                        </div>
                        <div class="p-3 rounded-xl bg-surface-50/50 border border-border space-y-1">
                            <div class="text-[11px] text-ink-muted uppercase tracking-wider font-semibold">Sharpe Ratio</div>
                            <div class="text-lg font-mono font-bold text-accent-200">
                                {{ (selectedCandidate.sharpe_ratio ?? 0).toFixed(2) }}
                            </div>
                        </div>
                        <div class="p-3 rounded-xl bg-surface-50/50 border border-border space-y-1">
                            <div class="text-[11px] text-ink-muted uppercase tracking-wider font-semibold">Out-Of-Sample PF</div>
                            <div class="text-lg font-mono font-bold text-emerald-400">
                                {{ (selectedCandidate.oos_pf ?? 1).toFixed(2) }}
                            </div>
                        </div>
                    </div>

                    <!-- In-Sample vs Out-of-Sample Split -->
                    <div class="p-4 rounded-xl bg-surface-50/30 border border-border space-y-3">
                        <h4 class="text-xs font-bold text-ink uppercase tracking-wider">Walk-Forward Validation Regimes</h4>
                        <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
                            <div class="p-3 rounded-lg bg-surface border border-border space-y-1">
                                <div class="font-semibold text-accent-200">In-Sample Training (2014 – 2022)</div>
                                <div class="flex justify-between text-ink-muted">
                                    <span>Trades: <strong class="text-ink">{{ selectedCandidate.is_trades ?? 0 }}</strong></span>
                                    <span>PF: <strong class="text-ink">{{ (selectedCandidate.is_pf ?? 1).toFixed(2) }}</strong></span>
                                    <span>Net: <strong class="text-emerald-400">+${{ (selectedCandidate.is_profit ?? 0).toFixed(2) }}</strong></span>
                                </div>
                            </div>
                            <div class="p-3 rounded-lg bg-surface border border-emerald-500/30 bg-emerald-500/5 space-y-1">
                                <div class="font-semibold text-emerald-400">Blind Out-Of-Sample (2023 – 2026)</div>
                                <div class="flex justify-between text-ink-muted">
                                    <span>Trades: <strong class="text-ink">{{ selectedCandidate.oos_trades ?? 0 }}</strong></span>
                                    <span>PF: <strong class="text-emerald-400">{{ (selectedCandidate.oos_pf ?? 1).toFixed(2) }}</strong></span>
                                    <span>Net: <strong class="text-emerald-400">+${{ (selectedCandidate.oos_profit ?? 0).toFixed(2) }}</strong></span>
                                </div>
                            </div>
                        </div>
                    </div>

                    <!-- Strategy Rules -->
                    <div v-if="selectedCandidate.rules_summary" class="p-4 rounded-xl bg-surface-50/30 border border-border space-y-2">
                        <h4 class="text-xs font-bold text-ink uppercase tracking-wider">Strategy Logic & Signals</h4>
                        <p class="text-xs font-mono text-ink-muted bg-surface p-2.5 rounded-lg border border-border">
                            {{ selectedCandidate.rules_summary }}
                        </p>
                    </div>

                    <div class="flex items-center justify-between pt-2">
                        <button @click="closeCandidateModal" class="btn btn-secondary text-xs">Close</button>
                        <a :href="'/trading/backtesting?candidate=' + encodeURIComponent(selectedCandidate.strategy_name)"
                           class="btn btn-primary text-xs inline-flex items-center gap-1.5">
                            Open in Hermes Backtesting
                            <ArrowRight class="w-3.5 h-3.5" />
                        </a>
                    </div>
                </div>
            </div>
"""

    if "<!-- Candidate Detail Modal -->" not in content:
        content = content.replace("        </div>\n    </AuthenticatedLayout>", "        </div>\n" + modal_markup + "    </AuthenticatedLayout>")

    path.write_text(content, encoding="utf-8")
    print("Updated StrategyCreation.vue successfully.")


def update_backtesting_vue():
    path = HERMES_ROOT / "resources/js/Pages/Trading/Backtesting.vue"
    content = path.read_text(encoding="utf-8")

    # Update props
    old_props = """const props = defineProps<{
    results: BacktestResult[];
}>();"""

    new_props = """const props = defineProps<{
    results: BacktestResult[];
    candidates?: any[];
    selectedCandidate?: string | null;
}>();"""

    if old_props in content:
        content = content.replace(old_props, new_props)

    # Add candidate computed
    if "const matchedCandidate = computed(" not in content:
        helper_code = """
const matchedCandidate = computed(() => {
    if (!props.selectedCandidate || !props.candidates) return null;
    return props.candidates.find((c: any) => c.strategy_name.toLowerCase() === props.selectedCandidate?.toLowerCase()) || null;
});
"""
        content = content.replace("const totalResults = computed(() => props.results.length);", "const totalResults = computed(() => props.results.length);" + helper_code)

    # Add Candidate Banner & Table if results.length === 0 or candidate selected
    old_empty_state = """                <!-- No results -->
                <div v-if="results.length === 0" class="card p-8 text-center">
                    <p class="text-ink-muted">No backtest results yet. Upload your first PDF.</p>
                    <button @click="showUploadForm = true" class="btn btn-primary mt-4">+ Upload PDF</button>
                </div>"""

    new_candidate_view = """                <!-- Active Candidate Strategy Performance Banner -->
                <div v-if="matchedCandidate" class="card mb-6 p-6 space-y-4 border-2 border-accent-200/50 bg-gradient-to-r from-accent-200/5 via-surface to-surface">
                    <div class="flex items-center justify-between border-b border-border pb-3">
                        <div class="space-y-1">
                            <div class="flex items-center gap-2">
                                <span class="px-2.5 py-0.5 rounded-full text-xs font-semibold bg-accent-200/20 text-accent-200 border border-accent-200/30">
                                    Validated Candidate
                                </span>
                                <h3 class="text-lg font-bold text-ink font-mono">{{ matchedCandidate.strategy_name }}</h3>
                                <span class="px-2 py-0.5 rounded text-xs font-semibold"
                                      :class="matchedCandidate.side === 'LONG' ? 'bg-emerald-500/10 text-emerald-400' : 'bg-red-500/10 text-red-400'">
                                    {{ matchedCandidate.side }}
                                </span>
                            </div>
                            <p class="text-xs text-ink-muted">Asset: {{ matchedCandidate.asset }} | Discovered: {{ matchedCandidate.created_at }}</p>
                        </div>
                        <a href="/trading/strategy-creation" class="btn btn-secondary text-xs">
                            ← Back to Strategy Factory
                        </a>
                    </div>

                    <!-- Performance Grid -->
                    <div class="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-7 gap-3">
                        <div class="p-3 rounded-xl bg-surface-50/60 border border-border">
                            <div class="text-[10px] text-ink-muted uppercase tracking-wider font-semibold">Net Profit</div>
                            <div class="text-base font-mono font-bold"
                                 :class="(matchedCandidate.net_profit ?? 0) >= 0 ? 'text-emerald-400' : 'text-red-400'">
                                {{ (matchedCandidate.net_profit ?? 0) >= 0 ? '+' : '' }}${{ (matchedCandidate.net_profit ?? 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}
                            </div>
                        </div>
                        <div class="p-3 rounded-xl bg-surface-50/60 border border-border">
                            <div class="text-[10px] text-ink-muted uppercase tracking-wider font-semibold">Profit Factor</div>
                            <div class="text-base font-mono font-bold text-accent-200">
                                {{ (matchedCandidate.profit_factor ?? 1).toFixed(2) }}
                            </div>
                        </div>
                        <div class="p-3 rounded-xl bg-surface-50/60 border border-border">
                            <div class="text-[10px] text-ink-muted uppercase tracking-wider font-semibold">Win Rate</div>
                            <div class="text-base font-mono font-bold text-ink">
                                {{ (matchedCandidate.win_rate ?? 0).toFixed(1) }}%
                            </div>
                        </div>
                        <div class="p-3 rounded-xl bg-surface-50/60 border border-border">
                            <div class="text-[10px] text-ink-muted uppercase tracking-wider font-semibold">Max Drawdown</div>
                            <div class="text-base font-mono font-bold text-red-400">
                                ${{ (matchedCandidate.max_drawdown ?? 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}
                            </div>
                        </div>
                        <div class="p-3 rounded-xl bg-surface-50/60 border border-border">
                            <div class="text-[10px] text-ink-muted uppercase tracking-wider font-semibold">Total Trades</div>
                            <div class="text-base font-mono font-bold text-ink">
                                {{ matchedCandidate.trades ?? 0 }}
                            </div>
                        </div>
                        <div class="p-3 rounded-xl bg-surface-50/60 border border-border">
                            <div class="text-[10px] text-ink-muted uppercase tracking-wider font-semibold">Return / DD</div>
                            <div class="text-base font-mono font-bold text-ink">
                                {{ (matchedCandidate.ret_dd_ratio ?? 0).toFixed(2) }}
                            </div>
                        </div>
                        <div class="p-3 rounded-xl bg-surface-50/60 border border-border">
                            <div class="text-[10px] text-ink-muted uppercase tracking-wider font-semibold">Blind OOS PF</div>
                            <div class="text-base font-mono font-bold text-emerald-400">
                                {{ (matchedCandidate.oos_pf ?? 1).toFixed(2) }}
                            </div>
                        </div>
                    </div>

                    <!-- Split breakdown & Rules -->
                    <div class="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
                        <div class="p-3 rounded-lg bg-surface border border-border space-y-1">
                            <span class="font-semibold text-accent-200">In-Sample (2014 – 2022)</span>
                            <div class="text-ink-muted">Trades: <strong class="text-ink">{{ matchedCandidate.is_trades ?? 0 }}</strong> | PF: <strong class="text-ink">{{ (matchedCandidate.is_pf ?? 1).toFixed(2) }}</strong> | Net: <strong class="text-emerald-400">+${{ (matchedCandidate.is_profit ?? 0).toFixed(2) }}</strong></div>
                        </div>
                        <div class="p-3 rounded-lg bg-surface border border-emerald-500/30 bg-emerald-500/5 space-y-1">
                            <span class="font-semibold text-emerald-400">Blind Out-of-Sample (2023 – 2026)</span>
                            <div class="text-ink-muted">Trades: <strong class="text-ink">{{ matchedCandidate.oos_trades ?? 0 }}</strong> | PF: <strong class="text-emerald-400">{{ (matchedCandidate.oos_pf ?? 1).toFixed(2) }}</strong> | Net: <strong class="text-emerald-400">+${{ (matchedCandidate.oos_profit ?? 0).toFixed(2) }}</strong></div>
                        </div>
                    </div>

                    <div v-if="matchedCandidate.rules_summary" class="text-xs font-mono text-ink-muted p-2 rounded bg-surface border border-border">
                        {{ matchedCandidate.rules_summary }}
                    </div>
                </div>

                <!-- Discovered Databank Candidate Strategies (shown when results is empty or alongside results) -->
                <div v-if="results.length === 0 && candidates && candidates.length > 0" class="space-y-4">
                    <div class="flex items-center justify-between">
                        <div>
                            <h3 class="text-base font-bold text-ink">Discovered Engine Candidates (Databank: Results)</h3>
                            <p class="text-xs text-ink-muted">Qualified quantitative strategies automatically synchronized from StrategyQuant Engine</p>
                        </div>
                        <span class="px-2.5 py-1 rounded-full text-xs font-semibold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                            {{ candidates.length }} Strategies Available
                        </span>
                    </div>

                    <div class="card overflow-x-auto">
                        <table class="w-full text-left text-xs">
                            <thead>
                                <tr class="border-b border-border bg-surface-50/50 text-ink-muted">
                                    <th class="py-2.5 px-4 font-medium">Strategy</th>
                                    <th class="py-2.5 px-3 font-medium">Asset</th>
                                    <th class="py-2.5 px-3 font-medium">Side</th>
                                    <th class="py-2.5 px-3 font-medium text-right">Net Profit</th>
                                    <th class="py-2.5 px-3 font-medium text-right">PF</th>
                                    <th class="py-2.5 px-3 font-medium text-right">Win Rate</th>
                                    <th class="py-2.5 px-3 font-medium text-right">Max DD</th>
                                    <th class="py-2.5 px-3 font-medium text-right">Trades</th>
                                    <th class="py-2.5 px-3 font-medium text-right">Ret/DD</th>
                                    <th class="py-2.5 px-3 font-medium text-right">OOS PF</th>
                                    <th class="py-2.5 px-4 font-medium text-center">Status</th>
                                </tr>
                            </thead>
                            <tbody class="divide-y divide-border">
                                <tr v-for="c in candidates" :key="c.filename"
                                    class="hover:bg-surface-50/50 transition cursor-pointer"
                                    :class="selectedCandidate?.toLowerCase() === c.strategy_name.toLowerCase() ? 'bg-accent-200/10' : ''"
                                    @click="router.visit('/trading/backtesting?candidate=' + encodeURIComponent(c.strategy_name))">
                                    <td class="py-3 px-4 font-bold text-ink font-mono">{{ c.strategy_name }}</td>
                                    <td class="py-3 px-3 text-ink">{{ c.asset }}</td>
                                    <td class="py-3 px-3">
                                        <span class="px-2 py-0.5 rounded text-[10px] font-semibold"
                                              :class="c.side === 'LONG' ? 'bg-emerald-500/10 text-emerald-400' : 'bg-red-500/10 text-red-400'">
                                            {{ c.side }}
                                        </span>
                                    </td>
                                    <td class="py-3 px-3 text-right font-mono font-semibold"
                                        :class="(c.net_profit ?? 0) >= 0 ? 'text-emerald-400' : 'text-red-400'">
                                        {{ (c.net_profit ?? 0) >= 0 ? '+' : '' }}${{ (c.net_profit ?? 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}
                                    </td>
                                    <td class="py-3 px-3 text-right font-mono font-semibold">
                                        <span class="px-2 py-0.5 rounded text-[11px]"
                                              :class="(c.profit_factor ?? 1) >= 1.25 ? 'bg-emerald-500/15 text-emerald-400 font-bold' : 'text-accent-200'">
                                            {{ (c.profit_factor ?? 1).toFixed(2) }}
                                        </span>
                                    </td>
                                    <td class="py-3 px-3 text-right font-mono text-ink">
                                        {{ (c.win_rate ?? 0).toFixed(1) }}%
                                    </td>
                                    <td class="py-3 px-3 text-right font-mono text-red-400/80">
                                        ${{ (c.max_drawdown ?? 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }}
                                    </td>
                                    <td class="py-3 px-3 text-right font-mono text-ink">
                                        {{ c.trades ?? 0 }}
                                    </td>
                                    <td class="py-3 px-3 text-right font-mono font-semibold"
                                        :class="(c.ret_dd_ratio ?? 0) >= 2.0 ? 'text-emerald-400' : 'text-ink'">
                                        {{ (c.ret_dd_ratio ?? 0).toFixed(2) }}
                                    </td>
                                    <td class="py-3 px-3 text-right font-mono font-semibold">
                                        <span :class="(c.oos_pf ?? 1) >= 1.3 ? 'text-emerald-400 font-bold' : 'text-accent-200'">
                                            {{ (c.oos_pf ?? 1).toFixed(2) }}
                                        </span>
                                    </td>
                                    <td class="py-3 px-4 text-center">
                                        <span class="px-2 py-0.5 rounded-full text-[10px] font-semibold bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                                            {{ c.status }}
                                        </span>
                                    </td>
                                </tr>
                            </tbody>
                        </table>
                    </div>
                </div>

                <!-- No results fallback if neither PDFs nor candidates exist -->
                <div v-else-if="results.length === 0" class="card p-8 text-center">
                    <p class="text-ink-muted">No backtest results yet. Upload your first PDF or start the StrategyQuant Factory.</p>
                    <button @click="showUploadForm = true" class="btn btn-primary mt-4">+ Upload PDF</button>
                </div>"""

    if old_empty_state in content:
        content = content.replace(old_empty_state, new_candidate_view)

    path.write_text(content, encoding="utf-8")
    print("Updated Backtesting.vue successfully.")


if __name__ == "__main__":
    update_algo_controller()
    update_strategy_creation_vue()
    update_backtesting_vue()
