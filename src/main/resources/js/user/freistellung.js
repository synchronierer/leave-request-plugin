(() => {
  const form = document.querySelector('#leave-form'), type = form.querySelector('[name=type]'), reason = document.querySelector('#reason-wrap'), note = document.querySelector('#personal-note'), out = document.querySelector('#form-message'), list = document.querySelector('#requests');
  const esc=s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const berlinTime = value => { if (!value) return ''; const date = new Date(value); return Number.isNaN(date.getTime()) ? String(value) : new Intl.DateTimeFormat('de-DE', {dateStyle:'medium', timeStyle:'short', timeZone:'Europe/Berlin'}).format(date); };
  const detail = document.querySelector('#request-detail'), detailContent = document.querySelector('#request-detail-content');
  const showDetail = x => {
    if (!x) return;
    const rows = [['Antragsteller',x.applicant],['Antragsart',x.type],['Schuljahr',x.schoolYear],['Antragstellung',berlinTime(x.createdAt)],['Zeitraum',`${x.startDate}–${x.endDate}`],['Stunden',`${x.startHour}–${x.endHour}`],['Anlass / Begründung',x.reason],['Status',x.status],['Entscheidung',x.slDecision||x.orgaDecision]].filter(([,value]) => value != null && value !== '');
    const group = x.groupName ? `<dt>Lerngruppe (historisch)</dt><dd>${esc(x.groupName)}</dd>` : '';
    detailContent.innerHTML = `<h2>Freistellungsantrag</h2><dl>${rows.map(([key,value]) => `<dt>${esc(key)}</dt><dd>${esc(value)}</dd>`).join('')}${group}</dl>${x.attachment?`<p><a href="/freistellung-attachment?id=${encodeURIComponent(x.id)}">PDF-Anhang öffnen</a></p>`:''}${x.slMessage?`<p><strong>Mitteilung:</strong> ${esc(x.slMessage)}</p>`:''}<h3>Bearbeitungshistorie</h3><ol>${(x.history||[]).map(h=>`<li>${esc(berlinTime(h.at))} · ${esc(h.status || '')}${h.message?` – ${esc(h.message)}`:''}</li>`).join('')}</ol>`;
    detail.showModal();
  };
  detail.querySelector('[data-close-detail]').addEventListener('click', () => detail.close());
  const render = requests => {
    if (!requests.length) { list.textContent = 'Keine Freistellungsanträge gefunden.'; return; }
    list.innerHTML = requests.map(x=>`<article class="request"><button type="button" data-detail="${esc(x.id)}"><strong>${esc(x.type)}</strong> <span class="status">${esc(x.status)}</span></button><div>${esc(x.startDate)}–${esc(x.endDate)}, Stunde ${esc(x.startHour)}–${esc(x.endHour)}</div>${x.slMessage?`<p>${esc(x.slMessage)}</p>`:''}</article>`).join('');
    list.querySelectorAll('[data-detail]').forEach(button=>button.addEventListener('click',()=>showDetail(requests.find(x=>x.id===button.dataset.detail))));
  };
  const refresh = async () => { list.textContent='Lade Anträge …'; try { const response=await fetch('/freistellung-data',{method:'POST',headers:{'Content-Type':'application/json'},body:'{}'}); if(!response.ok)throw new Error('Anträge konnten nicht geladen werden.'); const requests=await response.json(); if(!Array.isArray(requests))throw new Error('Ungültige Antwort vom Server.'); render(requests); } catch(error) { list.textContent=error.message||'Anträge konnten nicht geladen werden.'; } };
  const update = () => { const personal=type.value==='PERSOENLICHE_GRUENDE'; reason.hidden=false; reason.querySelector('textarea').required=!personal; note.hidden=!personal; };
  type.addEventListener('change',update);
  form.addEventListener('submit',async event=>{ event.preventDefault(); out.textContent='Antrag wird gespeichert …'; try { const data=new FormData(form); if(type.value==='PERSOENLICHE_GRUENDE')data.set('reason','nach Rücksprache mit der Schulleitung'); const response=await fetch('/freistellung-submit',{method:'POST',body:data}); let payload={}; try{payload=await response.json();}catch(_){} if(!response.ok)throw new Error(payload.error||'Antrag konnte nicht gespeichert werden.'); out.textContent='Antrag eingereicht.'; form.reset(); update(); await refresh(); } catch(error) { out.textContent=error.message||'Antrag konnte nicht gespeichert werden.'; } });
  update(); refresh();
})();
