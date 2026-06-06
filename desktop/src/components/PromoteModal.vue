<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useControlPlane } from '@/composables/useControlPlane'
import type { BrokerAccount, PromoteGateThresholds } from '@/types/control-plane'

const props = defineProps<{
  strategyId: string
  show: boolean
}>()

const emit = defineEmits<{
  close: []
  promoted: []
}>()

const { getBrokerAccounts, promoteStrategy, getPromoteGates, updatePromoteGates, error: apiError } = useControlPlane()

const targetMode = ref<'PAPER' | 'LIVE'>('PAPER')
const selectedAccountId = ref('default')
const customExecutionLabel = ref('')
const accounts = ref<BrokerAccount[]>([])
const loadingAccounts = ref(true)
const submitting = ref(false)
const validationChecks = ref<any[] | null>(null)
const generalError = ref<string | null>(null)

// Thresholds editing state
const thresholds = ref<PromoteGateThresholds>({
  minTrades: 100,
  maxDrawdownPct: 15.0,
  minReturnPct: -50.0,
  goldenReturnTolerancePct: 1.0,
  paperDaysBeforeLive: 30,
  validationModuleEnabled: false
})
const loadingThresholds = ref(true)
const savingThresholds = ref(false)
const thresholdMessage = ref<string | null>(null)

const selectedAccount = computed(() => {
  return accounts.value.find((acc) => acc.id === selectedAccountId.value)
})

// Auto-determine execution label based on mode and provider
const computedExecutionLabel = computed(() => {
  if (customExecutionLabel.value) return customExecutionLabel.value
  const provider = selectedAccount.value?.provider || 'OANDA'
  if (targetMode.value === 'PAPER') {
    if (provider === 'IBKR') return 'PAPER_IBKR'
    if (selectedAccount.value?.configured) return 'PAPER_OANDA'
    return 'PAPER_STUB'
  } else {
    return provider === 'IBKR' ? 'LIVE_IBKR' : 'LIVE_OANDA'
  }
})

onMounted(async () => {
  try {
    const [accs, gates] = await Promise.all([
      getBrokerAccounts(),
      getPromoteGates()
    ])
    accounts.value = accs
    thresholds.value = gates
  } catch (err: any) {
    generalError.value = `Failed to load settings: ${err.message}`
  } finally {
    loadingAccounts.value = false
    loadingThresholds.value = false
  }
})

async function submitPromotion() {
  submitting.value = true
  generalError.value = null
  validationChecks.value = null

  try {
    const res = await promoteStrategy(
      props.strategyId,
      targetMode.value,
      undefined, // auto-latest Completed Run
      computedExecutionLabel.value,
      selectedAccountId.value
    )

    if (res.promoted) {
      emit('promoted')
      emit('close')
    } else {
      validationChecks.value = res.checks || []
      generalError.value = 'Strategy failed to pass the required promotion gates.'
    }
  } catch (err: any) {
    generalError.value = err.message || 'Promotion request failed.'
  } finally {
    submitting.value = false
  }
}

async function saveThresholds() {
  savingThresholds.value = true
  thresholdMessage.value = null
  try {
    const updated = await updatePromoteGates(thresholds.value)
    thresholds.value = updated
    thresholdMessage.value = 'Gate thresholds updated successfully!'
    // Clear validation checks as they were run against old thresholds
    validationChecks.value = null
  } catch (err: any) {
    thresholdMessage.value = `Error: ${err.message}`
  } finally {
    savingThresholds.value = false
  }
}
</script>

<template>
  <div v-if="show" class="modal-overlay" @click.self="emit('close')">
    <div class="modal-card">
      <div class="modal-header">
        <h3>Promote Strategy</h3>
        <button class="close-btn" @click="emit('close')">×</button>
      </div>

      <div class="modal-body">
        <div class="field">
          <label>Strategy ID</label>
          <code class="strategy-id-badge">{{ strategyId }}</code>
        </div>

        <div class="field">
          <label>Target Mode</label>
          <div class="mode-toggle">
            <button
              type="button"
              :class="['toggle-btn', { active: targetMode === 'PAPER' }]"
              @click="targetMode = 'PAPER'"
            >
              Paper Trading
            </button>
            <button
              type="button"
              :class="['toggle-btn', { active: targetMode === 'LIVE' }]"
              @click="targetMode = 'LIVE'"
            >
              Live Trading
            </button>
          </div>
        </div>

        <div class="field">
          <label>Broker Account</label>
          <div v-if="loadingAccounts" class="loading-inline">
            <span class="spinner-small"></span> Loading accounts...
          </div>
          <select v-else v-model="selectedAccountId">
            <option v-for="acc in accounts" :key="acc.id" :value="acc.id">
              {{ acc.id }} ({{ acc.provider }} - {{ acc.maskedAccountId }})
            </option>
          </select>
          <div v-if="selectedAccount" class="account-status">
            <span :class="['status-dot', { active: selectedAccount.configured }]"></span>
            {{ selectedAccount.configured ? 'Configured (credentials active)' : 'Credentials missing/unconfigured' }}
          </div>
        </div>

        <div class="field">
          <label>Resolved Execution Label</label>
          <div class="execution-label-preview">
            <code>{{ computedExecutionLabel }}</code>
            <span v-if="computedExecutionLabel === 'PAPER_STUB'" class="warning-badge">
              ⚠️ Stub mode: No live broker connection, trades are simulated in-memory.
            </span>
          </div>
        </div>

        <!-- Collapsible gate editor -->
        <details class="thresholds-accordion">
          <summary>⚙️ Edit Promotion Gate Thresholds</summary>
          <div v-if="loadingThresholds" class="loading-inline">
            <span class="spinner-small"></span> Loading thresholds...
          </div>
          <div v-else class="thresholds-form">
            <div class="grid-2">
              <div class="field-inline">
                <label>Min Trades</label>
                <input v-model.number="thresholds.minTrades" type="number" min="0" />
              </div>
              <div class="field-inline">
                <label>Max Drawdown (%)</label>
                <input v-model.number="thresholds.maxDrawdownPct" type="number" min="0" step="0.1" />
              </div>
              <div class="field-inline">
                <label>Min Return (%)</label>
                <input v-model.number="thresholds.minReturnPct" type="number" step="1" />
              </div>
              <div class="field-inline">
                <label>Paper Days Required</label>
                <input v-model.number="thresholds.paperDaysBeforeLive" type="number" min="0" />
              </div>
            </div>

            <label class="checkbox-container">
              <input v-model="thresholds.validationModuleEnabled" type="checkbox" />
              Enable Validation Checks (OOS, Stress Tests)
            </label>

            <button class="save-gates-btn" :disabled="savingThresholds" @click="saveThresholds">
              <span v-if="savingThresholds" class="spinner-small"></span>
              <span v-else>Save Gating Changes</span>
            </button>
            <div v-if="thresholdMessage" class="threshold-status-message">
              {{ thresholdMessage }}
            </div>
          </div>
        </details>

        <!-- Failure Gates -->
        <div v-if="validationChecks && validationChecks.length" class="gate-results">
          <h4>Promotion Gate Checks</h4>
          <div class="checks-list">
            <div
              v-for="check in validationChecks"
              :key="check.name"
              :class="['check-item', { passed: check.passed }]"
            >
              <span class="check-status">{{ check.passed ? '✓' : '✗' }}</span>
              <div class="check-info">
                <span class="check-name">{{ check.name }}</span>
                <span v-if="check.message" class="check-message">{{ check.message }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Generic Error -->
        <div v-if="generalError" class="general-error">
          {{ generalError }}
        </div>
      </div>

      <div class="modal-footer">
        <button class="cancel-btn" :disabled="submitting" @click="emit('close')">Cancel</button>
        <button
          class="submit-btn"
          :disabled="submitting || loadingAccounts"
          @click="submitPromotion"
        >
          <span v-if="submitting" class="spinner-small"></span>
          <span v-else>Promote Strategy</span>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.7);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  backdrop-filter: blur(4px);
}

.modal-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 12px;
  width: 100%;
  max-width: 500px;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.modal-header {
  padding: 1rem 1.25rem;
  border-bottom: 1px solid var(--border);
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.modal-header h3 {
  margin: 0;
  font-size: 1.15rem;
  font-weight: 600;
}

.close-btn {
  background: transparent;
  border: none;
  font-size: 1.5rem;
  color: var(--text-secondary);
  cursor: pointer;
  line-height: 1;
}

.close-btn:hover {
  color: var(--text-primary);
}

.modal-body {
  padding: 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  max-height: 500px;
  overflow-y: auto;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

label {
  font-size: 0.7rem;
  font-weight: 600;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

select, input[type="number"] {
  background: var(--bg-primary);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 0.5rem 0.65rem;
  color: var(--text-primary);
  font-size: 0.875rem;
  outline: none;
  transition: border-color 0.15s;
}

select:focus, input[type="number"]:focus {
  border-color: var(--accent);
}

.strategy-id-badge {
  font-family: monospace;
  background: var(--bg-primary);
  padding: 0.4rem 0.6rem;
  border-radius: 4px;
  border: 1px solid var(--border);
  font-size: 0.85rem;
}

.mode-toggle {
  display: grid;
  grid-template-columns: 1fr 1fr;
  background: var(--bg-primary);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 2px;
}

.toggle-btn {
  background: transparent;
  border: none;
  color: var(--text-secondary);
  padding: 0.5rem;
  font-size: 0.85rem;
  font-weight: 600;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s;
}

.toggle-btn.active {
  background: var(--accent);
  color: #000;
}

.account-status {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 0.75rem;
  color: var(--text-secondary);
  margin-top: 0.2rem;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-secondary);
}

.status-dot.active {
  background: var(--success);
}

.execution-label-preview {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}

.execution-label-preview code {
  background: var(--bg-primary);
  padding: 0.3rem 0.5rem;
  border-radius: 4px;
  align-self: flex-start;
  font-family: monospace;
  font-size: 0.8rem;
}

.warning-badge {
  color: #f59e0b;
  font-size: 0.75rem;
}

/* Thresholds Accordion styling */
.thresholds-accordion {
  border: 1px solid var(--border);
  background: rgba(255, 255, 255, 0.02);
  border-radius: 6px;
  padding: 0.5rem;
}

.thresholds-accordion summary {
  font-size: 0.8rem;
  font-weight: 600;
  color: var(--accent);
  cursor: pointer;
  user-select: none;
}

.thresholds-form {
  margin-top: 0.75rem;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  padding: 0.5rem;
  background: var(--bg-primary);
  border-radius: 6px;
  border: 1px solid var(--border);
}

.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.5rem;
}

.field-inline {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.field-inline label {
  font-size: 0.65rem;
}

.checkbox-container {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.75rem;
  color: var(--text-primary);
  cursor: pointer;
  margin: 0.25rem 0;
  text-transform: none;
}

.checkbox-container input {
  accent-color: var(--accent);
}

.save-gates-btn {
  background: transparent;
  border: 1px solid var(--accent);
  color: var(--accent);
  border-radius: 4px;
  padding: 0.4rem;
  font-size: 0.75rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
  align-self: flex-start;
}

.save-gates-btn:hover {
  background: rgba(217, 119, 6, 0.08);
}

.threshold-status-message {
  font-size: 0.75rem;
  color: var(--success);
}

.gate-results {
  border-top: 1px solid var(--border);
  padding-top: 0.75rem;
}

.gate-results h4 {
  margin: 0 0 0.5rem;
  font-size: 0.8rem;
  color: var(--text-secondary);
  text-transform: uppercase;
}

.checks-list {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}

.check-item {
  display: flex;
  gap: 0.6rem;
  align-items: flex-start;
  padding: 0.4rem 0.6rem;
  background: rgba(239, 68, 68, 0.05);
  border: 1px solid rgba(239, 68, 68, 0.15);
  border-radius: 6px;
  color: #fca5a5;
}

.check-item.passed {
  background: rgba(34, 197, 94, 0.05);
  border: 1px solid rgba(34, 197, 94, 0.15);
  color: #a7f3d0;
}

.check-status {
  font-weight: bold;
}

.check-info {
  display: flex;
  flex-direction: column;
}

.check-name {
  font-size: 0.8rem;
  font-weight: 600;
}

.check-message {
  font-size: 0.75rem;
  opacity: 0.8;
}

.general-error {
  background: rgba(239, 68, 68, 0.08);
  border: 1px solid rgba(239, 68, 68, 0.2);
  color: #fca5a5;
  padding: 0.6rem;
  border-radius: 6px;
  font-size: 0.8rem;
}

.modal-footer {
  padding: 1rem 1.25rem;
  border-top: 1px solid var(--border);
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
}

.cancel-btn {
  background: transparent;
  border: 1px solid var(--border);
  color: var(--text-primary);
  border-radius: 6px;
  padding: 0.5rem 1rem;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
}

.submit-btn {
  background: var(--accent);
  color: #000;
  border: none;
  border-radius: 6px;
  padding: 0.5rem 1rem;
  font-size: 0.85rem;
  font-weight: 600;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
}

.submit-btn:disabled, .cancel-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.spinner-small {
  width: 0.8rem;
  height: 0.8rem;
  border: 2px solid rgba(0, 0, 0, 0.2);
  border-top-color: #000;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}

@keyframes spin { to { transform: rotate(360deg); } }
</style>
