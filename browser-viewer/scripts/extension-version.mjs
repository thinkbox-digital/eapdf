// Browser manifests require numeric versions; package.json is the sole source.
export function extensionVersion(version) {
  const match = /^(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?$/.exec(version);
  if (!match) throw new Error(`Unsupported viewer version: ${version}`);
  const parts = match.slice(1).filter(value => value !== undefined);
  if (parts.some(value => String(Number(value)) !== value || Number(value) > 65535))
    throw new Error(`Invalid browser version: ${version}`);
  return parts.join('.');
}
