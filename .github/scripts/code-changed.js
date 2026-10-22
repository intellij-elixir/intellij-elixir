'use strict';
// Decides whether the test matrix has to run for this pull request head.
//
// The question is not "what did this pull request change" - a rebase onto a newer main rewrites every
// sha without changing the pull request's own code - but "is the code tree byte-identical to a head
// that already passed". So it hashes the tree with the non-code paths removed and compares that
// digest against the same digest at the last successful run's head. Equal digests cannot produce a
// different test result.
//
// Gate contract, the opposite of record-leg-status.js: this script must never let tests be skipped on
// a guess. Anything unknown - no previous run, an API error, an unreachable sha - prints why and
// answers `true` (run them). The failure mode it refuses is a green check that tested nothing; the
// failure mode it accepts is a wasted matrix.

const { setOutput, warn } = require('./actions.js');
const { git, codeDigest } = require('./code-digest.js');

function run() {
  const head = process.env.HEAD_SHA;
  const previous = process.env.PREVIOUS_SHA;

  if (!head) return { code: true, why: 'HEAD_SHA is not set' };

  // With no previous run to compare against - the first push of a branch - fall back to the merge
  // base with the base branch. That commit is an ancestor of the base branch, so its code passed CI
  // when it merged; a branch whose code matches it has changed no code, whatever its history looks
  // like. This is what lets a changelog-only pull request skip on its very first run.
  let basis = previous;
  let origin = 'the last successful run';
  if (!basis) {
    const base = process.env.BASE_REF;
    if (!base) return { code: true, why: 'no previous successful run and BASE_REF is not set' };
    try {
      basis = git('merge-base', `origin/${base}`, head).trim();
      origin = `the merge base with ${base}`;
    } catch {
      return { code: true, why: `no previous successful run, and no merge base with origin/${base}` };
    }
  }
  const previousSha = basis;
  if (previousSha === head) return { code: true, why: `${origin} is this same sha` };

  try {
    git('cat-file', '-e', `${previousSha}^{commit}`);
  } catch {
    return { code: true, why: `${origin} (${previousSha.slice(0, 9)}) is not reachable in this checkout` };
  }

  const before = codeDigest(previousSha);
  const after = codeDigest(head);
  return before === after
    ? { code: false, why: `code tree identical to ${origin} ${previousSha.slice(0, 9)} (digest ${after.slice(0, 12)})` }
    : { code: true, why: `code tree differs from ${origin} ${previousSha.slice(0, 9)}` };
}

let result;
try {
  result = run();
} catch (e) {
  // Never answer "skip" from an exception - see the gate contract above.
  result = { code: true, why: `detection failed, running tests: ${e.message.split('\n')[0]}` };
  warn(result.why);
}

console.log(`code changed: ${result.code} - ${result.why}`);
setOutput('code', String(result.code));
setOutput('reason', result.why);
