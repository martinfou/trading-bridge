import { chromium } from 'playwright'

async function inspectConsole() {
  console.log('🔍 Launching browser to capture all UI console errors and network failures...\n')
  const browser = await chromium.launch({ headless: true })
  const page = await browser.newPage()

  page.on('dialog', async dialog => {
    console.log(`[DIALOG ${dialog.type()}]:`, dialog.message())
    await dialog.accept()
  })

  const allLogs = []

  page.on('console', msg => {
    allLogs.push({ type: msg.type(), text: msg.text(), location: msg.location() })
    console.log(`[CONSOLE ${msg.type().toUpperCase()}]:`, msg.text())
  })

  page.on('pageerror', err => {
    console.log('🔥 [PAGE ERROR]:', err.toString())
  })

  page.on('requestfailed', req => {
    console.log('❌ [REQUEST FAILED]:', req.url(), req.failure())
  })

  const routes = [
    '/#/dashboard',
    '/#/wfa',
    '/#/data-manager',
    '/#/strategies',
    '/#/results',
    '/#/live-trading',
    '/#/compare'
  ]

  for (const route of routes) {
    console.log(`\n👉 ================= Testing route: ${route} =================`)
    await page.goto(`http://localhost:5173${route}`, { waitUntil: 'domcontentloaded', timeout: 5000 }).catch(err => console.log('Goto error:', err.message))
    await page.waitForTimeout(1000)

    if (route === '/#/dashboard') {
      console.log('   Testing Dashboard interactions...')
    }

    if (route === '/#/wfa') {
      console.log('   Testing WFA view interactions...')
      const btn = await page.$('button:has-text("Futures")')
      if (btn) await btn.click().catch(() => {})
      await page.waitForTimeout(300)
    }

    if (route === '/#/data-manager') {
      console.log('   Testing Data Manager interactions...')
      const manageBtn = await page.$('button.manage-btn')
      if (manageBtn) {
        await manageBtn.click().catch(() => {})
        await page.waitForTimeout(500)
        const closeBtn = await page.$('button.close-btn')
        if (closeBtn) await closeBtn.click().catch(() => {})
      }
      await page.waitForTimeout(500)
    }

    if (route === '/#/strategies') {
      console.log('   Testing Strategies view...')
      const card = await page.$('.strategy-card')
      if (card) await card.click().catch(() => {})
      await page.waitForTimeout(500)
    }

    if (route === '/#/results') {
      console.log('   Testing Results view...')
      await page.waitForTimeout(500)
    }

    if (route === '/#/live-trading') {
      console.log('   Testing Live Trading view...')
      await page.waitForTimeout(500)
    }

    if (route === '/#/compare') {
      console.log('   Testing Compare view...')
      await page.waitForTimeout(500)
    }
  }

  console.log('\n================ SUMMARY ================')
  const errors = allLogs.filter(l => l.type === 'error')
  const warnings = allLogs.filter(l => l.type === 'warning')
  console.log(`Total Errors: ${errors.length}`)
  console.log(`Total Warnings: ${warnings.length}`)
  if (errors.length > 0) {
    console.log('\nAll Errors:')
    errors.forEach((e, idx) => console.log(`${idx + 1}. [${e.type}] ${e.text} (${JSON.stringify(e.location)})`))
  }

  await browser.close()
}

inspectConsole().catch(console.error)
