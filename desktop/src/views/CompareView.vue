<script setup lang="ts">
</script>

<template>
  <div class="view">
    <h1>Compare</h1>
    <p class="subtitle">Side-by-side backtest comparison</p>
    <div class="placeholder-card">
      <p>Recent runs comparison table with superposed equity curves will appear here.</p>
    </div>
  </div>
</template>

<style scoped>
.view { max-width: 1200px; }
h1 { font-size: 1.5rem; margin-bottom: 0.25rem; }
.subtitle { color: var(--text-secondary); font-size: 0.875rem; margin-bottom: 1.5rem; }
.placeholder-card { background: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; padding: 2rem; color: var(--text-secondary); }
</style>
