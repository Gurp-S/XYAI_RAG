const fs = require('fs');
const css = fs.readFileSync('d:/lea/xyai/xyUser/src/styles.css', 'utf8');
let depth = 0;
let inString = false;
let strChar = '';
let lines = css.split('\n');
let errors = [];
for (let i = 0; i < lines.length; i++) {
  let line = lines[i];
  for (let j = 0; j < line.length; j++) {
    let c = line[j];
    let prev = j > 0 ? line[j-1] : '';
    if (inString) {
      if (c === strChar && prev !== '\\') inString = false;
      continue;
    }
    if (c === '"' || c === "'") { inString = true; strChar = c; continue; }
    if (c === '{') depth++;
    if (c === '}') depth--;
  }
  if (depth < 0) {
    errors.push({ line: i+1, text: line.trim().substring(0, 60) });
    depth = 0;
  }
}
console.log('Final depth:', depth);
if (depth !== 0) console.log('UNBALANCED - missing closing braces');
if (errors.length) {
  console.log('Extra } at lines:', JSON.stringify(errors));
} else {
  console.log('No extra closing braces');
}
