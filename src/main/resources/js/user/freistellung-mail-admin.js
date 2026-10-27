(() => {
  const $ = (id) => document.getElementById(id);
  const text = (node, value) => { node.textContent = value == null ? "" : String(value); };
  async function load() {
    const response = await fetch('/freistellung-mail-admin-data', { method: 'POST', headers: {'Content-Type':'application/json'}, body: '{}' });
    if (!response.ok) throw new Error('Mailverwaltung konnte nicht geladen werden.');
    const data = await response.json();
    text($('transport'), JSON.stringify(data.transport, null, 2));
    const profiles = $('profiles'); profiles.replaceChildren();
    for (const profile of data.profiles || []) {
      const row = document.createElement('tr');
      const roles = [profile.apply ? 'Antrag' : '', profile.orga ? 'Orga-Leitung' : '', profile.sl ? 'Schulleitung' : ''].filter(Boolean).join(', ');
      row.innerHTML = '<td></td><td></td><td><input type="email"></td><td><button type="button">Speichern</button></td>';
      text(row.children[0], profile.username); text(row.children[1], roles);
      const input = row.querySelector('input'); input.value = profile.notificationEmail || ''; input.setAttribute('aria-label', `Mailadresse ${profile.username}`);
      row.querySelector('button').addEventListener('click', async () => {
        const save = await fetch('/freistellung-mail-profile', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({username:profile.username, notificationEmail:input.value})});
        text($('mail-warning'), save.ok ? 'Mailadresse gespeichert.' : 'Mailadresse konnte nicht gespeichert werden.');
        if (save.ok) load();
      }); profiles.append(row);
    }
    const notifications = $('notifications'); notifications.replaceChildren();
    for (const item of data.notifications || []) { const row=document.createElement('tr'); row.innerHTML='<td></td><td></td><td></td><td></td><td></td><td></td>'; [item.createdAt,item.event,item.recipientEmail || item.recipient,item.state,item.subject,item.error].forEach((v,i)=>text(row.children[i],v)); notifications.append(row); }
  }
  $('test-form').addEventListener('submit', async (event) => { event.preventDefault(); const response=await fetch('/freistellung-mail-test',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({recipient:$('test-recipient').value})}); text($('test-result'),response.ok?'Testmail wurde im Versandprotokoll vorgemerkt.':'Testmail konnte nicht vorgemerkt werden.'); if(response.ok)load(); });
  load().catch((error) => text($('mail-warning'), error.message));
})();
