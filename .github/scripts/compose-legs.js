'use strict';

// Composes the test and plugin-verification legs from .github/ci-versions.json. It needs a job of its
// own because a strategy.matrix expression can read the github, needs, vars and inputs contexts only -
// never a file.
//
// `verify` is not projected onto a test leg: every test leg builds against IntelliJ IDEA
// (platformType=IU), and those products are verification targets only.
//
// A version pointer (LATEST-EAP-SNAPSHOT) is resolved here, once per run, and every leg gets the build
// it names. A pointer with no active pre-release for a product composes no legs for it.
//
// Which legs exist depends on the event. A pull request or a push to main gets the IDEA legs, the pairs
// marked `pull-request` in the declaration, and (on a pull request only) Windows when it changes the code
// Windows has found bugs in. Any other event - the nightly run, a manual run, tag.yml's release dispatch -
// gets every declared leg (run-events.js), so an event nobody listed composes the most, not the least.
// A run on main also drops the jobs merged-head-jobs.js says already passed over the same code.

const fs = require('fs');
const { readDeclaration, ideaVersions, baseline, beamAdditional, pullRequestPairs, verifyProducts } = require('./ci-versions');
const { isPointer, productCode, resolveBuild, noActivePrerelease } = require('./ide-releases');
const { setOutput, addSummary, fail } = require('./actions');
const { isFullRun } = require('./run-events');

// Windows has found bugs only in how paths are written, so a pull request runs it only when it changes
// the code that writes them. Other events run it unconditionally.
const WINDOWS_PATHS = [
  'src/org/elixir_lang/sdk/',
  'jps-builder/',
  'src/org/elixir_lang/run/',
  'src/org/elixir_lang/console/',
];

// The API lists at most this many files of a pull request; a full page means some are missing.
const FILE_LIST_LIMIT = 3000;

// PULL_REQUEST_FILES names a file with one changed path per line, written by the workflow. Null when
// it is not there, unreadable or possibly truncated: a gate answers "run it" for what it cannot establish.
function pullRequestFiles() {
  try {
    const files = fs.readFileSync(process.env.PULL_REQUEST_FILES, 'utf8').split('\n').filter(Boolean);
    return files.length > 0 && files.length < FILE_LIST_LIMIT ? files : null;
  } catch {
    return null;
  }
}

function includeWindows(event) {
  if (isFullRun(event)) return true;
  if (event === 'push') return false;
  const files = pullRequestFiles();
  return !files || files.some((file) => WINDOWS_PATHS.some((prefix) => file.startsWith(prefix)));
}

// A leg's `job` field is its job name: the workflows name the job from it, and the pull request's run
// lists it, so it is what reuse matches on. A reusable workflow's check is `<caller job id> / <job>`, and
// the caller job is `verify` in shared-test.yml. An informational leg is never reused: its job passes
// even when its tests failed.
const testJob = (leg) => leg.job;
const verifyJob = (leg) => `verify / ${leg.job}`;

function reusableJobs() {
  try {
    const jobs = JSON.parse(process.env.PASSED_JOBS || '[]');
    if (Array.isArray(jobs)) return new Set(jobs);
  } catch {
    // Falls through to the warning.
  }
  console.log('::warning title=Reusable jobs unreadable::PASSED_JOBS is not a JSON array, so every leg runs');
  return new Set();
}

// Rejects rather than deduplicates: upload-artifact refuses the second of a duplicate name, so a
// silently dropped leg leaves the aggregate reporting a smaller matrix as if that were all of it.
// Run before the matrix is emitted, so a collision costs a two-second job instead of twelve minutes.
function requireUniqueNames(names, title, consequence) {
  const duplicates = [...new Set(names.filter((name, index) => names.indexOf(name) !== index))];
  if (!duplicates.length) return;
  fail(
    `${duplicates.join(', ')} - ${consequence}. Remove the duplicate from .github/ci-versions.json,` +
      ' or widen the label in compose-legs.js if the entries differ in a field it leaves out.',
    title,
  );
}

async function main() {
  const declaration = readDeclaration();

  const minimumSupported = declaration.idea.minimumSupported;
  const base = baseline(declaration);
  const event = process.env.GITHUB_EVENT_NAME || '';
  const pairs = isFullRun(event) ? beamAdditional(declaration) : pullRequestPairs(declaration);
  const reusable = reusableJobs();

  // Keyed by product code as well as version: a pointer names a different build per product.
  const resolutions = new Map();
  const skipped = [];
  async function buildFor(code, version) {
    const key = `${code} ${version}`;
    if (!resolutions.has(key)) {
      resolutions.set(
        key,
        resolveBuild(code, version).then(
          ({ build, release }) => {
            if (!build) {
              const message = `${noActivePrerelease(version, code, release)}, legs skipped`;
              console.log(`::notice title=No active pre-release::${message}`);
              skipped.push(message);
            }
            return build;
          },
          (error) => fail(`resolving ${version} for ${code}: ${error.message}`, 'IDE version not resolved'),
        ),
      );
    }
    return resolutions.get(key);
  }

  // The label is the only name used downstream - the job, both artifacts, and the check named after the
  // Test Results artifact - so a leg group names the axis it varies and omits what is invariant within
  // it. Two constraints on the format: the discriminator goes first, because the checks graph truncates
  // names at roughly 24 characters, and `/` is illegal in an artifact name (hence `+` between Elixir and
  // OTP) though legal in a job name. It carries the declared version, not the build, so a pointer's
  // check keeps its name from one build to the next. The job name, which reuse matches on, adds the build
  // for a pointer, so a pass on an older build does not stand for the one it names now.
  // `continue-on-error` on either axis makes the leg informational; shared-test.yml reads only the leg's.
  const buildSuffix = (version, build) => (isPointer(version) ? ` ${build}` : '');
  const leg = async (os, idea, beam, label) => {
    const build = await buildFor('IU', idea.version);
    const continueOnError = Boolean(idea['continue-on-error'] || beam['continue-on-error']);
    return (
      build && {
        os,
        'idea-version': build,
        'java-version': idea.java,
        beam,
        label,
        job: `test (${label}${buildSuffix(idea.version, build)})`,
        'continue-on-error': continueOnError,
      }
    );
  };

  const legs = (
    await Promise.all([
      ...ideaVersions(declaration).map((idea) =>
        leg('ubuntu-24.04-arm', idea, base, `IDEA ${idea.version}`),
      ),
      ...pairs.map((beam) => leg('ubuntu-24.04-arm', minimumSupported, beam, `${beam.elixir}+${beam.otp}`)),
      includeWindows(event) && leg('windows-2025', minimumSupported, base, `Win25, IDEA ${minimumSupported.version}`),
    ])
  ).filter(Boolean);
  const reused = [];
  const dropReused = (entries, informational, jobName) =>
    entries.filter((entry) => {
      const reuse = !informational(entry) && reusable.has(jobName(entry));
      if (reuse) reused.push(jobName(entry));
      return !reuse;
    });
  const testLegs = dropReused(legs, (entry) => entry['continue-on-error'], testJob);

  // One leg per product x version, never several IDEs per verifier JVM - see shared-verify.yml.
  const allVerifyLegs = (
    await Promise.all(
      ideaVersions(declaration).flatMap((idea) =>
        verifyProducts(idea).map(async (product) => {
          let code;
          try {
            code = productCode(product);
          } catch (error) {
            fail(error.message, 'Unknown verification product');
          }
          const build = await buildFor(code, idea.version);
          return build && { product, version: idea.version, build, job: `${product} ${idea.version}${buildSuffix(idea.version, build)}` };
        }),
      ),
    )
  ).filter(Boolean);
  const verifyLegs = dropReused(allVerifyLegs, () => false, verifyJob);

  requireUniqueNames(
    legs.map((entry) => entry.label),
    'Duplicate test legs',
    'two test legs would share a job name, a check name and both artifact names',
  );
  requireUniqueNames(
    verifyLegs.map((entry) => entry.job),
    'Duplicate verification legs',
    'two verification jobs would share a name and a report artifact name',
  );

  setOutput('matrix', JSON.stringify({ include: testLegs }));
  // An empty include list is not a valid matrix, so the test job is gated on this too.
  setOutput('matrix-any', testLegs.length > 0 ? 'true' : 'false');
  setOutput('verify', JSON.stringify({ include: verifyLegs }));
  // The same goes for verify.
  setOutput('verify-any', verifyLegs.length > 0 ? 'true' : 'false');

  addSummary(
    [
      '### Test legs',
      '',
      '| leg | os | IDEA | JBR | Elixir | OTP | informational |',
      '| --- | --- | --- | --- | --- | --- | --- |',
      ...testLegs.map((entry) =>
        [
          '',
          entry.label,
          entry.os,
          entry['idea-version'],
          entry['java-version'],
          entry.beam.elixir,
          entry.beam.otp,
          entry['continue-on-error'],
          '',
        ].join(' | ').trim(),
      ),
      '',
      '### Verification legs',
      '',
      '| product | version | build |',
      '| --- | --- | --- |',
      ...verifyLegs.map((entry) => `| ${entry.product} | ${entry.version} | ${entry.build} |`),
      '',
      ...(reused.length
        ? ['### Reused', '', 'Passed over the same code on the merged pull request head:', '', ...reused.map((name) => `- ${name}`), '']
        : []),
      ...(skipped.length ? ['### Skipped', '', ...skipped.map((message) => `- ${message}`), ''] : []),
    ].join('\n'),
  );
}

main();
