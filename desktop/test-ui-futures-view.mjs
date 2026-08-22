import { chromium } from 'playwright'
import path from 'path'

const SCREENSHOT_DIR = path.resolve('./ui-test-screenshots')

async function testFuturesDataView() {
  console.log('🚀 Testing CME Futures & Equities Data Matrix in Data Manager UI...\n')
  const browser = await chromium.launch({ headless: true })
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })

  console.log('📌 Navigating to Data Manager...')
  await page.goto('http://localhost:5173/#/data-manager', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1000)

  console.log('  🖱️ Clicking "CME Micro/Mini Futures (4)" tab...')
  await page.click('button.category-tab.futures')
  await page.waitForTimeout(1500)
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, '03-data-manager-futures-active.png') })
  console.log('  📸 Screenshot captured: 03-data-manager-futures-active.png')

  console.log('  🖱️ Clicking "US Small/Mid Cap Equities (5)" tab...')
  await page.click('button.category-tab.equities')
  await page.waitForTimeout(1500)
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, '03-data-manager-equities-active.png') })
  console.log('  📸 Screenshot captured: 03-data-manager-equities-active.png')

  await browser.close()
  console.log('\n🎉 CME Futures & Equities Verification Complete!')
}

testFuturesDataView().catch(err => {
  console.error('Error during test:', err)
  process.exit(1)
})
