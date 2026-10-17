// VisionOttica — Sofia's brain (Vercel serverless function).
// Receives the visitor's question from the Android app and answers as Sofia using the Claude API.
// The Anthropic API key lives ONLY here, as the environment variable ANTHROPIC_API_KEY.
//
// Env vars (Vercel → Project → Settings → Environment Variables):
//   ANTHROPIC_API_KEY  required — from console.anthropic.com
//   SOFIA_APP_TOKEN    recommended — same value as the app's sofia_app_token
//   SOFIA_MODEL        optional — default "claude-haiku-5-5" (fast, low cost)

const SOFIA_SYSTEM = `Sei Sofia, consulente virtuale dei negozi VisionOttica ("Il tuo ottico di fiducia"), dentro una dimostrazione dei Ray-Ban Meta.
Rispondi SEMPRE in italiano, con tono caldo, naturale e umano, come una consulente in negozio. Le risposte vengono lette ad alta voce: massimo 3 frasi brevi, niente elenchi, niente markdown, niente emoji.
Non inventare funzioni né dati. Se non sei sicura (prezzi, disponibilità, promozioni, tempi di consegna), invita a chiedere al personale del negozio.
Fatti utili: Ray-Ban Meta Gen 3 (presentati al Meta Connect, settembre 2026): fotocamera nella montatura per foto e video dal punto di vista di chi li indossa, LED che segnala la registrazione; altoparlanti open-ear; 6 microfoni con riduzione del rumore; assistente Meta AI attivabile con "Hey Meta" e tasto azione; chiamate e messaggi a mani libere tramite il telefono; musica e podcast; traduzione in tempo reale in alcune lingue; autonomia fino a circa 9 ore, fino a 50 ore con la custodia; modelli Wayfarer, Aviator e Zena. VisionOttica monta anche lenti graduate e offre controllo della vista e consulenza sulla montatura.`;

const hits = new Map(); // best-effort rate limit per IP (resets when the instance recycles)

export default async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).json({ error: 'method_not_allowed' });
  if (process.env.SOFIA_APP_TOKEN && req.headers['x-app-token'] !== process.env.SOFIA_APP_TOKEN) return res.status(401).json({ error: 'unauthorized' });
  if (!process.env.ANTHROPIC_API_KEY) return res.status(503).json({ error: 'not_configured' });

  const ip = (req.headers['x-forwarded-for'] || '').split(',')[0] || 'x';
  const now = Date.now(), h = (hits.get(ip) || []).filter(t => now - t < 60_000);
  if (h.length >= 12) return res.status(429).json({ error: 'rate_limited' });
  h.push(now); hits.set(ip, h);

  let body = req.body;
  if (typeof body === 'string') { try { body = JSON.parse(body); } catch { body = {}; } }
  const turns = (Array.isArray(body?.turns) ? body.turns : [])
    .filter(t => t && (t.role === 'user' || t.role === 'assistant') && typeof t.content === 'string' && t.content.trim())
    .map(t => ({ role: t.role, content: t.content.slice(0, 1500) }))
    .slice(-9);
  while (turns.length && turns[0].role !== 'user') turns.shift();
  if (!turns.length || turns[turns.length - 1].role !== 'user') return res.status(400).json({ error: 'bad_request' });

  try {
    const r = await fetch('https://api.anthropic.com/v1/messages', {
      method: 'POST',
      headers: { 'x-api-key': process.env.ANTHROPIC_API_KEY, 'anthropic-version': '2023-06-01', 'content-type': 'application/json' },
      body: JSON.stringify({ model: process.env.SOFIA_MODEL || 'claude-haiku-5-5', max_tokens: 300, system: SOFIA_SYSTEM, messages: turns }),
    });
    const j = await r.json();
    if (!r.ok) return res.status(502).json({ error: 'upstream', detail: j?.error?.type || r.status });
    const text = (j.content || []).filter(c => c.type === 'text').map(c => c.text).join('').trim();
    return res.status(200).json({ text });
  } catch (e) {
    return res.status(502).json({ error: 'upstream' });
  }
}
