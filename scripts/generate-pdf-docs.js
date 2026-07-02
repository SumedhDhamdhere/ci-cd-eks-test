/**
 * Converts all docs/*.md files to styled HTML that can be printed to PDF.
 * Run: node scripts/generate-pdf-docs.js
 * Then open docs/ALL_DOCS.html in Chrome and File > Print > Save as PDF.
 *
 * No npm install required — uses only Node.js built-ins.
 */

const fs = require('fs');
const path = require('path');

const DOCS_DIR = path.join(__dirname, '..', 'docs');
const OUTPUT = path.join(DOCS_DIR, 'ALL_DOCS.html');

// Doc order for the combined PDF
const DOC_ORDER = [
  'MASTER_GUIDE.md',
  'ONBOARDING.md',
  'ARCHITECTURE.md',
  'DEVELOPER_GUIDE.md',
  'DEPLOYMENT.md',
  'AUTOSCALING.md',
  'MONITORING.md',
  'CI_CD.md',
];

// Minimal markdown → HTML converter (handles the patterns we use)
function md2html(md) {
  return md
    // Fenced code blocks (must come before inline code)
    .replace(/```(\w*)\n([\s\S]*?)```/g, (_, lang, code) => {
      if (lang === 'mermaid') return `<pre class="mermaid">${code.trim()}</pre>`;
      return `<pre class="code-block"><code class="lang-${lang}">${esc(code.trim())}</code></pre>`;
    })
    // Headers
    .replace(/^#### (.+)$/gm, '<h4>$1</h4>')
    .replace(/^### (.+)$/gm, '<h3>$1</h3>')
    .replace(/^## (.+)$/gm, '<h2>$1</h2>')
    .replace(/^# (.+)$/gm, '<h1>$1</h1>')
    // Horizontal rule
    .replace(/^---$/gm, '<hr>')
    // Bold
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    // Inline code
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    // Tables (| col | col |)
    .replace(/(\|.+\|\n)+/g, (table) => {
      const rows = table.trim().split('\n');
      let html = '<table>';
      rows.forEach((row, i) => {
        if (/^\|[-| :]+\|$/.test(row.trim())) return; // separator row
        const cells = row.split('|').filter((_, j, a) => j > 0 && j < a.length - 1);
        const tag = i === 0 ? 'th' : 'td';
        html += '<tr>' + cells.map(c => `<${tag}>${c.trim()}</${tag}>`).join('') + '</tr>';
      });
      return html + '</table>';
    })
    // Unordered lists
    .replace(/(^- .+\n?)+/gm, (block) => {
      const items = block.trim().split('\n').map(l => `<li>${l.replace(/^- /, '')}</li>`).join('');
      return `<ul>${items}</ul>`;
    })
    // Numbered lists
    .replace(/(^\d+\. .+\n?)+/gm, (block) => {
      const items = block.trim().split('\n').map(l => `<li>${l.replace(/^\d+\. /, '')}</li>`).join('');
      return `<ol>${items}</ol>`;
    })
    // Blockquotes
    .replace(/^> (.+)$/gm, '<blockquote>$1</blockquote>')
    // Paragraphs (double newline)
    .replace(/\n\n([^<\n].+)/g, '\n\n<p>$1</p>')
    // Line breaks within paragraphs
    .replace(/\n(?!<)/g, '\n');
}

function esc(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

const CSS = `
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
         max-width: 900px; margin: 0 auto; padding: 24px; color: #1a1a1a; font-size: 14px; }
  h1 { color: #0d47a1; border-bottom: 3px solid #0d47a1; padding-bottom: 8px; page-break-before: always; }
  h1:first-child { page-break-before: avoid; }
  h2 { color: #1565c0; border-bottom: 1px solid #90caf9; margin-top: 32px; }
  h3 { color: #1976d2; margin-top: 24px; }
  h4 { color: #333; }
  code { background: #f0f4f8; padding: 2px 5px; border-radius: 3px; font-family: 'Courier New', monospace; font-size: 12px; }
  pre.code-block { background: #1e1e1e; color: #d4d4d4; padding: 16px; border-radius: 6px;
                   overflow-x: auto; font-size: 12px; line-height: 1.5; white-space: pre-wrap; word-break: break-all; }
  pre.code-block code { background: none; color: inherit; padding: 0; }
  pre.mermaid { background: #fff; text-align: center; page-break-inside: avoid; margin: 20px 0; }
  table { border-collapse: collapse; width: 100%; margin: 16px 0; }
  th, td { border: 1px solid #ccc; padding: 8px 12px; text-align: left; }
  th { background: #e3f2fd; font-weight: 600; }
  tr:nth-child(even) { background: #fafafa; }
  blockquote { border-left: 4px solid #0d47a1; margin: 0; padding: 8px 16px; background: #e8f4fd; }
  hr { border: none; border-top: 2px solid #e0e0e0; margin: 32px 0; }
  ul, ol { padding-left: 24px; }
  li { margin: 4px 0; }
  strong { color: #1a237e; }
  .doc-title { background: #0d47a1; color: white; padding: 40px; text-align: center; border-radius: 8px; margin-bottom: 32px; }
  .doc-title h1 { color: white; border: none; page-break-before: avoid; }
  .doc-title p { color: #bbdefb; font-size: 16px; margin: 8px 0 0; }
  @media print {
    body { max-width: 100%; }
    pre.code-block { page-break-inside: avoid; }
    h2 { page-break-after: avoid; }
  }
`;

const HEAD = (title) => `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>${title}</title>
<style>${CSS}</style>
<script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"></script>
<script>
  if (window.mermaid) {
    mermaid.initialize({
      startOnLoad: true,
      theme: 'base',
      securityLevel: 'loose',
      fontFamily: 'Segoe UI, sans-serif',
      themeVariables: {
        primaryColor: '#e3f2fd', primaryTextColor: '#0d47a1', primaryBorderColor: '#1976d2',
        lineColor: '#546e7a', fontSize: '15px'
      },
      flowchart: { htmlLabels: true, curve: 'basis', nodeSpacing: 40, rankSpacing: 50 },
      sequence: { actorMargin: 40, width: 150, mirrorActors: false }
    });
  }
</script>
</head>
<body>
`;

const TITLE_CARD = `<div class="doc-title">
  <h1>E-Commerce Platform</h1>
  <p>Complete Documentation Suite · Spring Boot · Kafka · Kubernetes · Floci (AWS)</p>
  <p style="color:#90caf9; font-size:13px">Generated: ${new Date().toLocaleDateString()}</p>
</div>
`;

// Build combined doc AND one standalone HTML per doc (for per-doc PDFs)
const PDF_HTML_DIR = path.join(DOCS_DIR, 'pdf-src');
fs.mkdirSync(PDF_HTML_DIR, { recursive: true });

let allHtml = HEAD('E-Commerce Platform — Complete Documentation') + TITLE_CARD;
let found = 0;
const perDoc = [];

for (const docFile of DOC_ORDER) {
  const filePath = path.join(DOCS_DIR, docFile);
  if (!fs.existsSync(filePath)) { console.warn(`  SKIP (not found): ${docFile}`); continue; }
  const raw = fs.readFileSync(filePath, 'utf-8');
  const body = md2html(raw);
  allHtml += `\n<!-- ===== ${docFile} ===== -->\n<section>\n${body}\n</section>\n`;

  // standalone HTML for this single doc
  const base = docFile.replace(/\.md$/, '');
  const singleHtml = HEAD(base) + `<section>\n${body}\n</section>\n</body></html>`;
  const singlePath = path.join(PDF_HTML_DIR, base + '.html');
  fs.writeFileSync(singlePath, singleHtml, 'utf-8');
  perDoc.push(base);

  console.log(`  ✓ ${docFile}`);
  found++;
}
allHtml += '</body></html>';
fs.writeFileSync(OUTPUT, allHtml, 'utf-8');

console.log(`\n✅ ${found} docs`);
console.log(`   combined  → ${OUTPUT}`);
console.log(`   per-doc   → ${PDF_HTML_DIR}\\*.html`);
console.log(`   docs list: ${perDoc.join(' ')}`);
