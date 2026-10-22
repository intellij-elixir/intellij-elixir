'use strict';
// What counts as code, and the digest of it, shared by code-changed.js and merged-head-jobs.js so the
// two cannot disagree about whether two heads hold the same code.

const { execFileSync } = require('child_process');
const crypto = require('crypto');

// Paths that cannot change a test outcome. Everything else - including .github/workflows and
// gradle.properties, which decide how the tests run - counts as code.
const NON_CODE = [
  /^CHANGELOG\.md$/,
  /^changelog\.d\//,
  /^[^/]*\.md$/,
  /^docs\//,
  /^screenshots\//,
  /^\.github\/ISSUE_TEMPLATE\//,
  /^\.github\/PULL_REQUEST_TEMPLATE/,
];

const isNonCode = p => NON_CODE.some(re => re.test(p));

const git = (...args) => execFileSync('git', args, { encoding: 'utf8', maxBuffer: 1 << 28 });

// The tree listing, minus the non-code paths: mode, type, sha and path per entry, so a content change
// to any code file changes the digest.
function codeDigest(sha) {
  const lines = git('ls-tree', '-r', sha).split('\n').filter(Boolean);
  const kept = lines.filter(line => !isNonCode(line.split('\t').slice(1).join('\t')));
  return crypto.createHash('sha256').update(kept.join('\n')).digest('hex');
}

module.exports = { git, codeDigest };
