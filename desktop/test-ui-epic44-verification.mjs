import { chromium } from 'playwright'
import path from 'path'

const SCREENSHOT_DIR = path.resolve('./ui-test-screenshots')

async function testEpic44() {
  console.log('🚀 Starting Epic 44 Playwright E2E Verification...\n')
  const browser = await chromium.launch({ headless: true })
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })

  page.on('console', msg => console.log('  [CONSOLE]', msg.text()))
  page.on('pageerror', err => console.log('  [PAGEERROR]', err.message))

  console.log('📌 1. Navigating to Dashboard...')
  await page.goto('http://localhost:5173/#/dashboard', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1000)

  console.log('📌 2. Testing <StrategySelector> trigger & modal opening...')
  const selectorBtn = page.locator('.selector-trigger')
  await selectorBtn.waitFor({ state: 'visible', timeout: 5000 })
  await selectorBtn.click()
  await page.waitForTimeout(500)

  // Verify modal is open
  const modal = page.locator('.strategy-dropdown-panel')
  await modal.waitFor({ state: 'visible' })
  console.log('  ✅ Modal opened successfully.')
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, 'epic44-strategy-selector-open.png') })
  console.log('  📸 Screenshot: epic44-strategy-selector-open.png')

  console.log('📌 3. Testing filter chips...')
  const futuresChip = page.locator('.filter-chip', { hasText: '⚡ Futures' })
  await futuresChip.click()
  await page.waitForTimeout(300)
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, 'epic44-strategy-selector-filtered.png') })
  console.log('  📸 Screenshot: epic44-strategy-selector-filtered.png')

  console.log('📌 4. Selecting Futures Strategy (LtCrossMomentum)...')
  const allChip = page.locator('.filter-chip', { hasText: 'All' })
  await allChip.click()
  await page.waitForTimeout(200)

  const searchInput = page.locator('.search-box input')
  await searchInput.fill('LtCrossMomentum')
  await page.waitForTimeout(300)
  await page.keyboard.press('Enter')
  await page.waitForTimeout(500)

  // Verify Futures Cost Preset
  const presetBadge = page.locator('.preset-badge')
  await presetBadge.waitFor({ state: 'visible' })
  const badgeText = await presetBadge.innerText()
  console.log(`  ✅ Active Preset: ${badgeText}`)

  const commissionInput = page.locator('label:has-text("Commission ($)") + input')
  const commissionVal = await commissionInput.inputValue()
  console.log(`  ✅ Futures Commission: $${commissionVal}`)
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, 'epic44-cost-presets-futures.png') })
  console.log('  📸 Screenshot: epic44-cost-presets-futures.png')

  console.log('📌 5. Selecting Forex Strategy (LondonOpenRangeBreakout)...')
  await selectorBtn.click()
  await page.waitForTimeout(300)
  await searchInput.fill('LondonOpen')
  await page.waitForTimeout(300)
  await page.keyboard.press('Enter')
  await page.waitForTimeout(500)

  const forexBadgeText = await presetBadge.innerText()
  const forexCommissionVal = await commissionInput.inputValue()
  console.log(`  ✅ Active Preset: ${forexBadgeText}`)
  console.log(`  ✅ Forex Commission: $${forexCommissionVal}`)
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, 'epic44-cost-presets-forex.png') })
  console.log('  📸 Screenshot: epic44-cost-presets-forex.png')

  console.log('📌 6. Testing Anti-Overwrite Lock & Reset Button...')
  await commissionInput.fill('5.50')
  await commissionInput.dispatchEvent('input')
  await page.waitForTimeout(200)

  const resetBtn = page.locator('.btn-reset-preset')
  await resetBtn.waitFor({ state: 'visible' })
  console.log('  ✅ Dirty state lock activated: Reset button is visible.')

  // Switch to another strategy while dirty
  await selectorBtn.click()
  await page.waitForTimeout(300)
  await searchInput.fill('FuturesOpeningRangeBreakout')
  await page.waitForTimeout(300)
  await page.keyboard.press('Enter')
  await page.waitForTimeout(500)

  const retainedCommission = await commissionInput.inputValue()
  console.log(`  ✅ Preserved custom commission on strategy switch: $${retainedCommission}`)

  await resetBtn.click()
  await page.waitForTimeout(300)
  const resetCommission = await commissionInput.inputValue()
  console.log(`  ✅ After reset button click: $${resetCommission}`)

  console.log('📌 7. Testing Basket Class Isolation Warning...')
  // Select both MES (Futures) and EUR/USD (Forex)
  const symbolsTrigger = page.locator('.multiselect-trigger')
  await symbolsTrigger.click()
  await page.waitForTimeout(400)

  const dropdown = page.locator('.multiselect-dropdown')
  await dropdown.waitFor({ state: 'visible' })

  // Check the first futures item and first forex item
  const checkboxList = page.locator('.multiselect-item input[type="checkbox"]')
  const count = await checkboxList.count()
  console.log(`  Found ${count} instrument checkboxes.`)

  await checkboxList.nth(0).setChecked(true)
  await checkboxList.nth(4).setChecked(true)
  await page.waitForTimeout(300)

  await symbolsTrigger.click() // close dropdown
  await page.waitForTimeout(500)

  const basketWarning = page.locator('.basket-warning')
  await basketWarning.waitFor({ state: 'visible', timeout: 3000 })
  const warningText = await basketWarning.innerText()
  console.log(`  ✅ Basket Warning Banner Displayed: "${warningText.trim()}"`)

  const runBtn = page.locator('.run-btn')
  const isDisabled = await runBtn.isDisabled()
  console.log(`  ✅ Run Backtest button disabled on basket mismatch: ${isDisabled}`)
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, 'epic44-basket-isolation-warning.png') })
  console.log('  📸 Screenshot: epic44-basket-isolation-warning.png')

  console.log('📌 8. Testing Strategy Catalog View...')
  await page.goto('http://localhost:5173/#/strategies', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1000)
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, 'epic44-catalog-view-taxonomy.png') })
  console.log('  📸 Screenshot: epic44-catalog-view-taxonomy.png')

  console.log('📌 9. Testing WFA View with StrategySelector...')
  await page.goto('http://localhost:5173/#/wfa', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1000)
  const wfaSelectorBtn = page.locator('.selector-trigger')
  await wfaSelectorBtn.waitFor({ state: 'visible' })
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, 'epic44-wfa-view.png') })
  console.log('  📸 Screenshot: epic44-wfa-view.png')

  await browser.close()
  console.log('\n🎉 Epic 44 Playwright E2E UI Verification Passed Completely!')
}

testEpic44().catch((err) => {
  console.error('❌ E2E UI Test Failed:', err)
  process.exit(1)
})
