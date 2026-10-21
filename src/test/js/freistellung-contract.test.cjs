const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
test('plugin metadata and privacy contract exist',()=>{const root=path.join(__dirname,'..','..','main');const y=fs.readFileSync(path.join(root,'resources/plugin.yml'),'utf8');assert.match(y,/id: freistellung/);const js=fs.readFileSync(path.join(root,'resources/js/user/freistellung.js'),'utf8');assert.match(js,/slMessage/);assert.match(js,/esc=/)});
