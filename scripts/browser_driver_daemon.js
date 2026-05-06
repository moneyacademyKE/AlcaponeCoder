const { chromium } = require('playwright');
const readline = require('readline');

async function getAccessibilityTree(page) {
    return await page.evaluate(() => {
        function getTree(element, depth = 0) {
            const role = element.getAttribute('role') || element.tagName.toLowerCase();
            const name = element.innerText || element.getAttribute('aria-label') || element.getAttribute('placeholder') || '';
            let result = '  '.repeat(depth) + `${role} "${name.trim().substring(0, 50)}"`;
            
            if (['a', 'button', 'input', 'select'].includes(element.tagName.toLowerCase()) || element.getAttribute('role')) {
                const ref = element.getAttribute('data-hermes-ref') || 'e' + Math.random().toString(36).substr(2, 4);
                element.setAttribute('data-hermes-ref', ref);
                result += ` [ref=${ref}]`;
            }
            
            result += '\n';
            for (const child of element.children) {
                if (depth < 10) result += getTree(child, depth + 1);
            }
            return result;
        }
        return getTree(document.body);
    });
}

async function run() {
    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();
    const rl = readline.createInterface({ input: process.stdin });

    for await (const line of rl) {
        if (!line.trim()) continue;
        try {
            const command = JSON.parse(line);
            let result = '';
            
            switch (command.action) {
                case 'navigate':
                    await page.goto(command.url, { waitUntil: 'networkidle' });
                    result = await getAccessibilityTree(page);
                    break;
                case 'snapshot':
                    result = await getAccessibilityTree(page);
                    break;
                case 'click':
                    await page.click(`[data-hermes-ref="${command.ref}"]`);
                    result = `Clicked ${command.ref}`;
                    break;
                case 'type':
                    await page.fill(`[data-hermes-ref="${command.ref}"]`, command.text);
                    result = `Typed ${command.text} into ${command.ref}`;
                    break;
                case 'exit':
                    await browser.close();
                    process.exit(0);
                default:
                    throw new Error(`Unknown action: ${command.action}`);
            }
            process.stdout.write(JSON.stringify({ status: 'ok', result }) + '\n');
        } catch (err) {
            process.stdout.write(JSON.stringify({ status: 'error', message: err.message }) + '\n');
        }
    }
}

run();
