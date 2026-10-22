'use strict';
// The jobs of this workflow that need not run again on main because they passed on the pull request
// head that main's head merges. Consumed by compose-legs.js, which drops the legs these names stand for.
//
// A run on main whose head is a merge commit and whose code equals the pull request head's has nothing
// new to tell: the pull request already ran those jobs over that code. A pull request merged without a
// rebase has a different digest, so everything runs.
//
// Gate contract, as code-changed.js has: anything unknown - not a merge commit, no run for the pull
// request head, an API error - prints why and answers an empty list, so every leg runs. A wasted leg is
// the failure mode accepted; a skipped leg that never ran is the one refused.

const { git, codeDigest } = require('./code-digest.js');
const { execFileSync } = require('child_process');
const { setOutput, warn } = require('./actions.js');

// Runs on main. A pull request's own run has nothing to reuse, and a manual or release run asks for
// everything on purpose.
const EVENTS = new Set(['push', 'schedule']);

// A merge that changes one of these must run its legs, because main is the only ref that writes the
// caches the pull request legs restore, and each of these is a part of a cache key, a cache path or
// the content a cache holds. Skipping every leg would leave pull requests restoring a stale cache
// until some other leg happened to run. Keep it beside the rule, and from the keys as they are defined:
//   .github/ci-versions.json         JBR key (java level), Gradle User Home prefix (IDEA version),
//                                    quoter key (Elixir/OTP pair), corpus key (corpus)
//   shared-test.yml, shared-verify.yml, setup-env
//                                    the key and path expressions of the quoter, corpus, JBR and Gradle
//                                    caches, and the verification legs' own cache prefix
//   gradle.properties                hashed into the quoter key; holds quoterRef
//   buildSrc, *.gradle*, wrapper, version catalogs
//                                    what setup-gradle's Gradle User Home holds, and what the Gradle tasks
//                                    that emit the quoter and corpus cache paths and keys compute
const CACHE_INPUTS = [
  /^\.github\/ci-versions\.json$/,
  /^\.github\/workflows\/shared-test\.yml$/,
  /^\.github\/workflows\/shared-verify\.yml$/,
  /^\.github\/actions\/setup-env\//,
  /(^|\/)gradle\.properties$/,
  /^buildSrc\//,
  /(^|\/)[^/]*\.gradle[^/]*$/,
  /(^|\/)gradle-wrapper\.properties$/,
  /(^|\/)gradle\/[^/]*\.versions\.toml$/,
  /(^|\/)versions\.properties$/,
];

const gh = (...args) => execFileSync('gh', ['api', ...args], { encoding: 'utf8', maxBuffer: 1 << 28 });

function run() {
  const event = process.env.EVENT_NAME;
  const head = process.env.HEAD_SHA;
  const repo = process.env.REPO;
  if (!EVENTS.has(event)) return { jobs: [], why: `a ${event || 'unknown'} run reuses nothing` };
  if (!head || !repo) return { jobs: [], why: 'HEAD_SHA or REPO is not set' };

  const parents = git('rev-list', '--parents', '-n', '1', head).trim().split(' ').slice(1);
  if (parents.length !== 2) return { jobs: [], why: `${head.slice(0, 9)} is not a merge commit` };
  const second = parents[1];

  if (codeDigest(head) !== codeDigest(second)) {
    return { jobs: [], why: `code differs from the merged pull request head ${second.slice(0, 9)}` };
  }

  // Since the last run on this ref that succeeded, not since the first parent: a run that failed or was
  // cancelled after a cache input changed never refreshed the caches, and the merge after it would find
  // the input unchanged.
  const previous = process.env.PREVIOUS_SHA;
  if (!previous) return { jobs: [], why: 'no earlier successful run on this ref to compare cache inputs against' };
  try {
    git('cat-file', '-e', `${previous}^{commit}`);
  } catch {
    return { jobs: [], why: `the earlier successful run's head ${previous.slice(0, 9)} is not reachable in this checkout` };
  }

  const input = git('diff', '--name-only', previous, head)
    .split('\n')
    .find(path => path && CACHE_INPUTS.some(re => re.test(path)));
  if (input) return { jobs: [], why: `${input} changed since the last successful run (${previous.slice(0, 9)}), and a cache is keyed on or holds it` };

  // The pull request's run of this workflow on that head: the newest that concluded successfully.
  const run = JSON.parse(
    gh(`repos/${repo}/actions/workflows/shared-test.yml/runs?head_sha=${second}&event=pull_request&status=success&per_page=1`),
  ).workflow_runs[0];
  if (!run) return { jobs: [], why: `no successful pull request run for ${second.slice(0, 9)}` };

  // The legs of a pull request run check out its merge commit - the head merged with the base of that
  // moment - which the run does not report. shared-test.yml names a step after it, so it is read back here.
  const passed = gh(
    '--paginate',
    `repos/${repo}/actions/runs/${run.id}/jobs?per_page=100`,
    '--jq',
    '.jobs[] | select(.conclusion == "success") | {name, tested: ([.steps[]?.name | select(startswith("Tested commit "))] | first)} | tojson',
  )
    .split('\n')
    .filter(Boolean)
    .map(line => JSON.parse(line));
  const tested = (passed.map(job => /^Tested commit ([0-9a-f]{40})$/.exec(job.tested || '')).find(Boolean) || [])[1];
  if (!tested) return { jobs: [], why: `run ${run.id} does not say which commit it tested` };
  try {
    git('cat-file', '-e', `${tested}^{commit}`);
  } catch {
    try {
      git('fetch', '--no-tags', '--quiet', 'origin', tested);
    } catch {
      return { jobs: [], why: `the commit ${tested.slice(0, 9)} that run ${run.id} tested cannot be fetched` };
    }
  }
  if (codeDigest(tested) !== codeDigest(head)) {
    return { jobs: [], why: `run ${run.id} tested ${tested.slice(0, 9)}, whose code differs from this head's` };
  }

  const jobs = passed.map(job => job.name);
  return { jobs, why: `${jobs.length} jobs passed in run ${run.id} on the merged head ${second.slice(0, 9)}, over the same code` };
}

let result;
try {
  result = run();
} catch (e) {
  result = { jobs: [], why: `lookup failed, every leg runs: ${e.message.split('\n')[0]}` };
  warn(result.why);
}

console.log(`reusable jobs: ${result.jobs.length} - ${result.why}`);
setOutput('jobs', JSON.stringify(result.jobs));
setOutput('reason', result.why);
