import { chromium } from 'playwright'
import path from 'path'

const SCREENSHOT_DIR = path.resolve('./ui-test-screenshots')

async function testUniverseManager() {
  console.log('🚀 Testing Universe & Minicap Manager UI...\n')
  const browser = await chromium.launch({ headless: true })
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })

  console.log('📌 Navigating to Data Manager...')
  await page.goto('http://localhost:5173/#/data-manager', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1000)

  console.log('  🖱️ Clicking "⚡ Manage Universe" button...')
  await page.click('button.manage-btn')
  await page.waitForTimeout(1000)
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, '03-universe-drawer-open.png') })
  console.log('  📸 Screenshot captured: 03-universe-drawer-open.png')

  console.log('  🧺 Clicking "Small-Cap Core Basket" (IJR, VB, SCHA)...')
  await page.click('button.basket-btn:has-text("Small-Cap Core Basket")')
  await page.waitForTimeout(1500)
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, '03-universe-basket-imported.png') })
  console.log('  📸 Screenshot captured: 03-universe-basket-imported.png')

  console.log('  🖱️ Closing drawer and switching to US Small/Mid Cap Equities category...')
  await page.click('button.close-btn')
  await page.waitForTimeout(800)

  await page.click('button.category-tab.equities')
  await page.waitForTimeout(1500)
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, '03-universe-matrix-with-minicaps.png') })
  console.log('  📸 Screenshot captured: 03-universe-matrix-with-minicaps.png')

  console.log('  📥 Selecting IJR and triggering ingestion for 2026...')
  await page.selectOption('select.form-select >> nth=0', 'ijr')
  await page.waitForTimeout(300)
  await page.selectOption('select.form-select >> nth=1', 'h1')
  await page.waitForTimeout(300)
  await page.selectOption('select.form-select >> nth=2', 'single')
  await page.waitForTimeout(300)
  await page.selectOption('select.form-select >> nth=3', '2026')
  await page.waitForTimeout(300)

  await page.click('button.download-btn')
  await page.waitForTimeout(2000)
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, '03-universe-ijr-ingestion-active.png') })
  console.log('  📸 Screenshot captured: 03-universe-ijr-ingestion-active.png')

  await browser.close()
  console.log('\n🎉 Universe & Minicap Management E2E Test Succeeded!')
}

testUniverseManager().catch(err => {
  console.error('Error during test:', err)
  process.exit(1)
})
