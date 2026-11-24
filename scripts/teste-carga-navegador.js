// Teste de carga do Taxify pelo navegador - só leituras (nada sincroniza nem envia para SIEG/SEFAZ/e-Fisco).
// Como usar: logado no Taxify, abra o DevTools (F12) > Console, cole este arquivo inteiro e Enter.
// Roda 4 degraus de 90s com 5, 10, 20 e 30 usuários simultâneos (1 a 3s de pausa entre cliques).
// No fim (~6 min) imprime a tabela por degrau e por tela; copie o resultado e mande para análise.
(async () => {
  const API = location.origin + '/taxify-service/api/v1';
  const H = { Authorization: 'Bearer ' + localStorage.getItem('taxify_token'), 'Content-Type': 'application/json' };
  const comps = await (await fetch(API + '/companies?page=0&size=50', { headers: H })).json();
  const list = comps.content || comps;
  const ids = list.map((c) => c.id);
  const kdm = list.find((c) => String(c.document || c.cnpj || '').replace(/\D/g, '') === '11594485000105');
  const rnd = (a) => a[Math.floor(Math.random() * a.length)];
  const filter = (id) => ({ companyId: id, dataEmissaoInicio: '2026-10-01', dataEmissaoFim: '2026-10-31', pagination: { page: 0, size: 20 } });
  const reqs = [
    ['pendencias', () => fetch(API + '/pendencias?status=ABERTA&page=0&size=50', { headers: H })],
    ['pendencias-resumo', () => fetch(API + '/pendencias/resumo', { headers: H })],
    ['apuracao-mes', () => fetch(API + '/fiscal/apuracao?month=2026-10', { headers: H })],
    ['empresas', () => fetch(API + '/companies?page=0&size=10', { headers: H })],
    ['notas-lista', () => fetch(API + '/integrations/xmls', { method: 'POST', headers: H, body: JSON.stringify(filter(rnd(ids))) })],
    ['notas-totais', () => fetch(API + '/integrations/xmls/totais', { method: 'POST', headers: H, body: JSON.stringify(filter(rnd(ids))) })],
    ['fronteira', () => fetch(API + '/fronteira?companyId=' + (kdm ? kdm.id : rnd(ids)), { headers: H })],
  ];
  const results = [];
  const stages = {};
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  let target = 0;
  let done = false;
  let active = 0;
  const vu = async (n) => {
    while (!done && n < target) {
      const [name, fn] = rnd(reqs);
      const t0 = performance.now();
      let status = 0;
      try {
        const r = await fn();
        status = r.status;
        await r.arrayBuffer();
      } catch {
        status = -1;
      }
      results.push({ stage: target, name, status, ms: Math.round(performance.now() - t0) });
      await sleep(1000 + Math.random() * 2000);
    }
  };
  console.log(`Teste de carga: ${ids.length} empresas, KDM ${kdm ? 'achada' : 'não achada'}. ~6 min...`);
  for (const users of [5, 10, 20, 30]) {
    target = users;
    stages[users] = new Date().toTimeString().slice(0, 8);
    console.log(`${stages[users]} - degrau ${users} usuários`);
    while (active < target) {
      vu(active);
      active++;
    }
    await sleep(90000);
  }
  done = true;
  await sleep(4000);
  const pct = (arr, p) => arr[Math.min(arr.length - 1, Math.floor((p / 100) * arr.length))] ?? 0;
  const summarize = (rows) => {
    const ms = rows.map((r) => r.ms).sort((a, b) => a - b);
    const errors = rows.filter((r) => r.status < 200 || r.status >= 400).length;
    return { req: rows.length, erros: errors, p50: pct(ms, 50), p95: pct(ms, 95), max: ms.at(-1) ?? 0 };
  };
  const byStage = Object.keys(stages).map((s) => ({ usuarios: +s, inicio: stages[s], ...summarize(results.filter((r) => r.stage === +s)) }));
  const byName = [...new Set(results.map((r) => r.name))].map((n) => ({ tela: n, ...summarize(results.filter((r) => r.name === n)) }));
  const statuses = results.reduce((acc, r) => ({ ...acc, [r.status]: (acc[r.status] || 0) + 1 }), {});
  console.table(byStage);
  console.table(byName);
  const out = JSON.stringify({ byStage, byName, statuses }, null, 1);
  console.log(out);
  try {
    await navigator.clipboard.writeText(out);
    console.log('Resultado copiado para a área de transferência.');
  } catch {
    /* sem permissão de clipboard: copie o JSON acima */
  }
})();
