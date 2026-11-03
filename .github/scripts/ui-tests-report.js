'use strict';

// Reports a red nightly UI run: comments on the open issue titled TITLE that github-actions opened, or opens it.
// A green run never closes it; someone does, once they have read it.
//
// Contract of a gate, not of record-leg-status.js: a bug here must show as a red step, so every failure is a
// fail(), never a swallowed warning. DRY_RUN=1 reads the repository and prints the issue it would act on and the
// body, and writes nothing.
//
// Env: GH_TOKEN, REPO, RUN_ID, SHA, PREVIOUS_SHA (empty when none was found or the lookup failed), STEPS (the job's `steps`
// context as JSON), RESULTS_DIR (the testUI XML reports, default build/test-results/testUI), DRY_RUN.

const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const { fail } = require('./actions.js');

const TITLE = 'Nightly UI tests failed';
const AUTHOR = 'app/github-actions';
// GitHub rejects a body past 65536 characters; the rest is room for each test's heading, fence and clip note.
const MAX_BODY = 60000;
const ROOM_PER_TEST = 150;

function gh(args, input) {
  return execFileSync('gh', args, { encoding: 'utf8', input, maxBuffer: 1 << 26 });
}

function xmlText(s) {
  return s.replace(/&(#x[0-9a-fA-F]+|#[0-9]+|lt|gt|amp|quot|apos);/g, (_, e) => {
    if (e[0] !== '#') return { lt: '<', gt: '>', amp: '&', quot: '"', apos: "'" }[e];
    return String.fromCodePoint(e[1] === 'x' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10));
  });
}

function attributes(tag) {
  const found = {};
  for (const m of tag.matchAll(/([\w:.-]+)="([^"]*)"/g)) found[m[1]] = xmlText(m[2]);
  return found;
}

// Each failed or errored testcase of one report: { name, message }. CDATA is dropped first, because
// system-out can hold anything and a failure's message is an attribute, not its body.
function failedTests(xml) {
  const found = [];
  const text = xml.replace(/<!\[CDATA\[[\s\S]*?\]\]>/g, '');
  for (const m of text.matchAll(/<testcase\b([^>]*?)(\/>|>([\s\S]*?)<\/testcase>)/g)) {
    const body = m[3] || '';
    const problem = /<(failure|error)\b([^>]*)/.exec(body);
    if (!problem) continue;
    const testcase = attributes(m[1]);
    const message = attributes(problem[2]).message || '';
    found.push({ name: `${testcase.classname || ''} > ${testcase.name || ''}`, message });
  }
  return found;
}

function failedTestsIn(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir).filter(f => f.endsWith('.xml')).sort()
    .flatMap(f => failedTests(fs.readFileSync(path.join(dir, f), 'utf8')));
}

// A fence longer than any run of backticks inside the text, so a message holding one cannot close it.
function fenced(text) {
  const longest = Math.max(0, ...(text.match(/`+/g) || []).map(r => r.length));
  const fence = '`'.repeat(Math.max(3, longest + 1));
  return `${fence}\n${text}\n${fence}`;
}

function clip(text, limit) {
  if (text.length <= limit) return text;
  const kept = Math.max(0, limit);
  return `${text.slice(0, kept)}\n... (${text.length - kept} more characters; the full report is in the run's ui-tests artifact)`;
}

function body({ repo, runId, sha, previousSha, steps, tests }) {
  const lines = [
    `The nightly UI tests failed: [run ${runId}](https://github.com/${repo}/actions/runs/${runId}).`,
    '',
    `- Commit tested: [\`${sha.slice(0, 9)}\`](https://github.com/${repo}/commit/${sha})`,
    previousSha
      ? `- Since the last green run: [\`${previousSha.slice(0, 9)}...${sha.slice(0, 9)}\`](https://github.com/${repo}/compare/${previousSha}...${sha})`
      : '- Since the last green run: not known (none was found, or the lookup failed; the "Find the last successful run" step says which).',
    '',
    '### Failed steps',
    '',
    ...(steps.length ? steps.map(s => `- \`${s}\``) : ['- none; the run failed without a step recording a failure']),
    '',
    '### Failed tests',
    '',
  ];
  if (!tests.length) lines.push('No test failed.');
  // A completion row's reading is the whole lookup, so one message can be long. The shortest messages keep all
  // their text and the longer ones split what is left, so a long reading cannot push other tests' rows out.
  let room = MAX_BODY - lines.join('\n').length - tests.reduce((sum, t) => sum + t.name.length + ROOM_PER_TEST, 0);
  const limits = new Map();
  [...tests].sort((a, b) => a.message.length - b.message.length).forEach((t, i, sorted) => {
    const limit = Math.min(t.message.length, Math.max(0, Math.floor(room / (sorted.length - i))));
    limits.set(t, limit);
    room -= limit;
  });
  for (const t of tests) lines.push(`**${t.name}**`, '', fenced(clip(t.message, limits.get(t))), '');
  return lines.join('\n');
}

try {
  const repo = process.env.REPO;
  const runId = process.env.RUN_ID;
  const sha = process.env.SHA;
  if (!repo || !runId || !sha) fail('ui-tests-report.js needs REPO, RUN_ID and SHA');
  const previousSha = process.env.PREVIOUS_SHA || '';
  const dryRun = process.env.DRY_RUN === '1';
  const stepOutcomes = JSON.parse(process.env.STEPS || '{}');
  const steps = Object.keys(stepOutcomes).filter(id => stepOutcomes[id].outcome === 'failure');
  const tests = failedTestsIn(process.env.RESULTS_DIR || 'build/test-results/testUI');
  const text = body({ repo, runId, sha, previousSha, steps, tests });

  const open = JSON.parse(gh([
    'issue', 'list', '--repo', repo, '--state', 'open', '--author', AUTHOR,
    '--search', `"${TITLE}" in:title`, '--json', 'number,title', '--limit', '100',
  ]));
  // Oldest first: if two are open, the first one keeps the history.
  const issue = open.filter(i => i.title === TITLE).sort((a, b) => a.number - b.number)[0];

  if (dryRun) {
    console.log(issue ? `would comment on #${issue.number}` : `would create "${TITLE}"`);
    console.log(text);
  } else if (issue) {
    gh(['issue', 'comment', String(issue.number), '--repo', repo, '--body-file', '-'], text);
    console.log(`commented on #${issue.number}`);
  } else {
    console.log(gh(['issue', 'create', '--repo', repo, '--title', TITLE, '--body-file', '-'], text).trim());
  }
} catch (e) {
  fail(`ui-tests-report.js: ${e.message.split('\n')[0]}`);
}
