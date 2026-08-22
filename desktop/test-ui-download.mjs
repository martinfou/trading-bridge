import { chromium } from 'playwright'
import path from 'path'

const SCREENSHOT_DIR = path.resolve('./ui-test-screenshots')

async function testDownload() {
  console.log('🚀 Testing Historical Data Ingestion via Data Manager UI...\n')
  const browser = await chromium.launch({ headless: true })
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } })

  console.log('📌 Navigating to Data Manager...')
  await page.goto('http://localhost:5173/#/data-manager', { waitUntil: 'networkidle' })
  await page.waitForTimeout(1000)

  console.log('  🖱️ Selecting Instrument: USD/CHF, Granularity: H1, Year: 2026...')
  await page.selectOption('select.form-select >> nth=0', 'usdchf')
  await page.waitForTimeout(300)
  await page.selectOption('select.form-select >> nth=1', 'h1')
  await page.waitForTimeout(300)
  await page.selectOption('select.form-select >> nth=2', 'single')
  await page.waitForTimeout(300)
  await page.selectOption('select.form-select >> nth=3', '2026')
  await page.waitForTimeout(300)

  console.log('  🖱️ Clicking "Start Ingestion" button...')
  await page.click('button.download-btn')
  await page.waitForTimeout(1500)
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, '03-data-manager-ingesting.png') })
  console.log('  📸 Screenshot captured: 03-data-manager-ingesting.png')

  console.log('  ⏳ Waiting for ingestion and binary bars generation to complete...')
  await page.waitForTimeout(5000)

  await page.click('button.btn.secondary:has-text("Refresh")')
  await page.waitForTimeout(1000)
  await page.screenshot({ path: path.join(SCREENSHOT_DIR, '03-data-manager-after-download.png') })
  console.log('  📸 Screenshot captured: 03-data-manager-after-download.png')

  await browser.close()
  console.log('\n🎉 Historical Data Download Test Complete!')
}

testDownload().catch(err => {
  console.error('Error testing download:', err)
  process.exit(1)
})
