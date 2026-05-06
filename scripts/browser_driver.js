const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

async function getAccessibilityTree(page) {
    return await page.evaluate(() => {
        function getTree(element, depth = 0) {
            const role = element.getAttribute('role') || element.tagName.toLowerCase();
            const name = element.innerText || element.getAttribute('aria-label') || element.getAttribute('placeholder') || '';
            let result = '  '.repeat(depth) + `${role} "${name.trim().substring(0, 50)}"`;
            
            // Add a mock ref for now (simulating what the agent expects)
            if (['a', 'button', 'input', 'select'].includes(element.tagName.toLowerCase()) || element.getAttribute('role')) {
                const ref = 'e' + Math.random().toString(36).substr(2, 4);
                element.setAttribute('data-hermes-ref', ref);
                result += ` [ref=${ref}]`;
            }
            
            result += '\n';
            
            for (const child of element.children) {
                // Only go deep for relevant elements to avoid explosion
                if (depth < 5) {
                    result += getTree(child, depth + 1);
                }
            }
            return result;
        }
        return getTree(document.body);
    });
}

async function main() {
    const args = process.argv.slice(2);
    const command = JSON.parse(args[0]);
    
    const browser = await chromium.launch({ headless: true });
    const page = await browser.newPage();

    try {
        let result = '';
        switch (command.action) {
            case 'navigate':
                await page.goto(command.url, { waitUntil: 'networkidle' });
                const tree = await getAccessibilityTree(page);
                result = `Navigated to ${command.url}\n\n${tree}`;
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
            default:
                throw new Error(`Unknown action: ${command.action}`);
        }
        process.stdout.write(JSON.stringify({ status: 'ok', result }) + '\n');
    } catch (err) {
        process.stdout.write(JSON.stringify({ status: 'error', message: err.message }) + '\n');
    } finally {
        await browser.close();
    }
}

main();
