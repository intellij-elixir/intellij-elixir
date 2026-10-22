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

const { readDeclaration, ideaVersions, baseline, beamAdditional, verifyProducts } = require('./ci-versions');
const { productCode, resolveBuild, noActivePrerelease } = require('./ide-releases');
const { setOutput, addSummary, fail } = require('./actions');

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
  // check keeps its name from one build to the next.
  // `continue-on-error` on either axis makes the leg informational; shared-test.yml reads only the leg's.
  //
  // `suite` picks what the leg runs. The code intelligence matrix answers to the IDE version alone - every
  // Elixir/OTP pair and Windows gave identical cells - so it runs once per IDEA version on a `matrix` leg,
  // and the `full` legs leave it out. The matrix records what the plugin does today, red cells included, so
  // its legs are informational: they run and report their counts, and never block the merge.
  const leg = async (os, idea, beam, label, suite = 'full') => {
    const build = await buildFor('IU', idea.version);
    const continueOnError = Boolean(idea['continue-on-error'] || beam['continue-on-error']);
    return (
      build && {
        os,
        'idea-version': build,
        'java-version': idea.java,
        beam,
        label,
        suite,
        'continue-on-error': continueOnError,
      }
    );
  };

  const legs = (
    await Promise.all([
      ...ideaVersions(declaration).map((idea) =>
        leg('ubuntu-24.04-arm', idea, base, `IDEA ${idea.version}`),
      ),
      ...beamAdditional(declaration).map((beam) =>
        leg('ubuntu-24.04-arm', minimumSupported, beam, `${beam.elixir}+${beam.otp}`),
      ),
      leg('windows-2025', minimumSupported, base, `Win25, IDEA ${minimumSupported.version}`),
      ...ideaVersions(declaration).map((idea) =>
        leg('ubuntu-24.04-arm', idea, { ...base, 'continue-on-error': true }, `matrix, IDEA ${idea.version}`, 'matrix'),
      ),
    ])
  ).filter(Boolean);

  // One leg per product x version, never several IDEs per verifier JVM - see shared-verify.yml.
  const verifyLegs = (
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
          return build && { product, version: idea.version, build };
        }),
      ),
    )
  ).filter(Boolean);

  requireUniqueNames(
    legs.map((entry) => entry.label),
    'Duplicate test legs',
    'two test legs would share a job name, a check name and both artifact names',
  );
  requireUniqueNames(
    verifyLegs.map((entry) => `${entry.product} ${entry.version}`),
    'Duplicate verification legs',
    'two verification jobs would share a name and a report artifact name',
  );

  setOutput('matrix', JSON.stringify({ include: legs }));
  setOutput('verify', JSON.stringify({ include: verifyLegs }));
  // An empty include list is not a valid matrix, so the verify job is gated on this instead.
  setOutput('verify-any', verifyLegs.length > 0 ? 'true' : 'false');

  addSummary(
    [
      '### Test legs',
      '',
      '| leg | suite | os | IDEA | JBR | Elixir | OTP | informational |',
      '| --- | --- | --- | --- | --- | --- | --- | --- |',
      ...legs.map((entry) =>
        [
          '',
          entry.label,
          entry.suite,
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
      ...(skipped.length ? ['### Skipped', '', ...skipped.map((message) => `- ${message}`), ''] : []),
    ].join('\n'),
  );
}

main();
