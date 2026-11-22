import { createHash } from 'node:crypto';
import { lstat, mkdir, mkdtemp, readFile, readdir, rename, rm, rmdir } from 'node:fs/promises';
import { join } from 'node:path';

async function existing(path) {
  try { return await lstat(path); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
}

async function inventory(directory, prefix = '') {
  const files = Object.create(null);
  for (const item of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, item.name);
    const name = prefix + item.name;
    if (item.isDirectory()) Object.assign(files, await inventory(path, name + '/'));
    else if (item.isFile()) files[name] = createHash('sha256').update(await readFile(path)).digest('hex');
    else throw new Error('Unexpected build entry: ' + path);
  }
  return files;
}

async function matchesBuild(directory) {
  const manifest = join(directory, 'build-info.json');
  if (!(await existing(manifest))?.isFile()) return false;
  try {
    const { files } = JSON.parse(await readFile(manifest, 'utf8'));
    const actual = await inventory(directory);
    delete actual['build-info.json'];
    return Object.keys(actual).length === Object.keys(files).length
      && Object.entries(actual).every(([name, hash]) => files[name] === hash);
  } catch {
    return false;
  }
}

/** Install verified stage directories, preserving any previous build containing unmanaged or edited files. */
export async function publishViewerBuilds(stage, dist, browsers) {
  await mkdir(dist, { recursive: true });
  if (!(await lstat(dist)).isDirectory()) throw new Error('Viewer dist is not a regular directory: ' + dist);
  for (const browser of browsers) {
    if (!await matchesBuild(join(stage, browser))) throw new Error('Incomplete staged viewer build: ' + browser);
  }

  const lock = join(dist, '.publish-lock');
  await mkdir(lock);
  try {
    await installBuilds(stage, dist, browsers);
  } finally {
    await rmdir(lock);
  }
}

async function installBuilds(stage, dist, browsers) {
  const previous = await mkdtemp(join(dist, '.preserved-'));
  const moved = [];
  const published = [];
  try {
    for (const browser of browsers) {
      const destination = join(dist, browser);
      const current = await existing(destination);
      if (current && !current.isDirectory()) {
        throw new Error('Refusing to replace a non-directory viewer build: ' + destination);
      }
      if (current) {
        await rename(destination, join(previous, browser));
        moved.push(browser);
      }
      await rename(join(stage, browser), destination);
      published.push(browser);
    }
  } catch (error) {
    try {
      for (const browser of published.reverse()) await rename(join(dist, browser), join(stage, browser));
      for (const browser of moved.reverse()) await rename(join(previous, browser), join(dist, browser));
      await rmdir(previous);
    } catch (rollbackFailure) {
      throw new AggregateError([error, rollbackFailure], 'Viewer build replacement failed. Previous files: ' + previous);
    }
    throw error;
  }

  for (const browser of moved) {
    const oldBuild = join(previous, browser);
    if (await matchesBuild(oldBuild)) await rm(oldBuild, { recursive: true });
  }
  if ((await readdir(previous)).length) console.warn('Preserved previous viewer files in ' + previous);
  else await rmdir(previous);
}
