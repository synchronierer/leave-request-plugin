const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
test('plugin metadata, privacy and SMTP contract exist',()=>{const root=path.join(__dirname,'..','..','main');const y=fs.readFileSync(path.join(root,'resources/plugin.yml'),'utf8');assert.match(y,/id: freistellung/);const js=fs.readFileSync(path.join(root,'resources/js/user/freistellung.js'),'utf8');assert.match(js,/slMessage/);assert.match(js,/esc=/);const smtp=fs.readFileSync(path.join(root,'java/de/igslandstuhl/database/freistellung/SmtpTransport.java'),'utf8');assert.match(smtp,/ARCANUM_FREISTELLUNG_SMTP_CONFIG/);assert.match(smtp,/implicit/);assert.match(smtp,/GROUP_READ/);});

test('Freistellung uses explicit permission rights and stores audit function',()=>{
  const root=path.join(__dirname,'..','..','main');
  const source=fs.readFileSync(path.join(root,'java/de/igslandstuhl/database/freistellung/FreistellungPlugin.java'),'utf8');
  assert.match(source,/freistellung_apply/); assert.match(source,/freistellung_orga/); assert.match(source,/freistellung_sl/);
  assert.match(source,/PluginLoader/); assert.match(source,/getPluginInfos/); assert.match(source,/permission-manager/); assert.match(source,/classLoader/);
  assert.match(source,/function_name/); assert.match(source,/hasPermission/); assert.doesNotMatch(source,/hasRole\(user/);
  const paths=JSON.parse(fs.readFileSync(path.join(root,'resources/meta/paths/get_paths.json'),'utf8'));
  assert.ok(paths['/freistellung-bearbeitung']);
});

test('mail uses Arcanum profiles, current rights and a fake transport by default',()=>{
  const root=path.join(__dirname,'..','..','main');
  const source=fs.readFileSync(path.join(root,'java/de/igslandstuhl/database/freistellung/FreistellungPlugin.java'),'utf8');
  assert.match(source,/freistellung_user_profiles/); assert.match(source,/notification_email/);
  assert.match(source,/User\.getAllUsers\(\)/); assert.match(source,/notificationEmail/);
  assert.match(source,/BLOCKED_NO_RECIPIENT/); assert.match(source,/recipient_email/);
  assert.match(source,/freistellung-mail-admin-data/); assert.match(source,/user\.isAdmin\(\)/);
  assert.match(source,/RUECKSPRACHE/); assert.match(source,/ORGA_ABGESCHLOSSEN/);
  const js=fs.readFileSync(path.join(root,'resources/js/user/freistellung-mail-admin.js'),'utf8');
  assert.match(js,/textContent/); assert.match(js,/freistellung-mail-profile/);
  const applicant=fs.readFileSync(path.join(root,'resources/js/user/freistellung.js'),'utf8');
  assert.doesNotMatch(applicant,/data-withdraw/);
});

test('mail admin handlers use the shared active-mode admin authorization',()=>{
  const root=path.join(__dirname,'..','..','main');
  const source=fs.readFileSync(path.join(root,'java/de/igslandstuhl/database/freistellung/FreistellungPlugin.java'),'utf8');
  assert.match(source,/AccessManager\.getInstance\(\)\.isAdminRouteAuthorized/);
  assert.equal((source.match(/mailAdminAuthorized\(rq,user\)/g)||[]).length,3);
  assert.doesNotMatch(source,/if\(!user\.isAdmin\(\)\)return PostResponse\.forbidden/);
  const postPaths=JSON.parse(fs.readFileSync(path.join(root,'resources/meta/paths/post_paths.json'),'utf8'));
  for (const route of ['/freistellung-mail-admin-data','/freistellung-mail-profile','/freistellung-mail-test']) assert.equal(postPaths[route].access_level,'admin');
});

test('applicant history is server-side allow-listed and never contains reviewer metadata',()=>{
  const root=path.join(__dirname,'..','..','main');
  const source=fs.readFileSync(path.join(root,'java/de/igslandstuhl/database/freistellung/FreistellungPlugin.java'),'utf8');
  assert.match(source,/return reviewer \? o : applicantView\(o\)/);
  assert.match(source,/static JsonObject applicantView\(JsonObject internal\)/);
  const view=source.slice(source.indexOf('static JsonObject applicantView'),source.indexOf('private JsonArray history'));
  for (const field of ['actor','function','fromStatus','toStatus','detail','orgaComment','slInternalComment']) {
    assert.doesNotMatch(view,new RegExp(`(?:addProperty|add)\\(\\\"${field}\\\"`));
  }
  assert.match(view,/safe.add\(\"history\", publicHistory\)/);
});

test('missing-mail warnings are idempotent under the notification uniqueness contract',()=>{
  const root=path.join(__dirname,'..','..','main');
  const source=fs.readFileSync(path.join(root,'java/de/igslandstuhl/database/freistellung/FreistellungPlugin.java'),'utf8');
  assert.match(source,/INSERT OR IGNORE INTO freistellung_notifications\(request_id,recipient,event,state/);
});

test('application UI keeps PDF optional, preserves legacy groups and formats Berlin time',()=>{
  const root=path.join(__dirname,'..','..','main');
  const html=fs.readFileSync(path.join(root,'resources/html/user/freistellung.html'),'utf8');
  const js=fs.readFileSync(path.join(root,'resources/js/user/freistellung.js'),'utf8');
  const css=fs.readFileSync(path.join(root,'resources/css/user/freistellung.css'),'utf8');
  assert.match(html,/PDF-Anhang \(freiwillig/);
  assert.doesNotMatch(html,/id="group-wrap"/);
  assert.match(js,/Europe\/Berlin/);
  assert.match(js,/historisch/);
  assert.match(html,/teacher-dashboard/);
  assert.doesNotMatch(css,/Georgia|#f7f4ec|#7d4e35/);
});

test('review UI has terminal loading states and a bounded authenticated data path',()=>{
  const root=path.join(__dirname,'..','..','main');
  const html=fs.readFileSync(path.join(root,'resources/html/user/freistellung-bearbeitung.html'),'utf8');
  const css=fs.readFileSync(path.join(root,'resources/css/user/freistellung.css'),'utf8');
  assert.match(html,/data-state="loading"/);
  assert.match(html,/data-state='empty'|setListState\('empty'/);
  assert.match(html,/setListState\('error'/);
  assert.match(html,/new AbortController\(\)/);
  assert.match(html,/fetchFreistellungJson/);
  assert.doesNotMatch(html,/const fetchJson=/);
  assert.match(html,/credentials:'same-origin'/);
  assert.match(html,/cache:'no-store'/);
  assert.match(css,/\[data-state="error"\]/);
});

test('application fields use the teacher dashboard form language',()=>{
  const root=path.join(__dirname,'..','..','main');
  const html=fs.readFileSync(path.join(root,'resources/html/user/freistellung.html'),'utf8');
  const css=fs.readFileSync(path.join(root,'resources/css/user/freistellung.css'),'utf8');
  assert.match(html,/class="leave-form"/);
  assert.match(html,/class="leave-field-grid"/);
  assert.match(html,/field-hint/);
  assert.match(css,/\.leave-form-actions/);
  assert.match(css,/focus-visible/);
});
