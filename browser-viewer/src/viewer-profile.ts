/** Compile-time profile: shared code, optional implementations omitted by bundling. */
export const enum ViewerFeature { LargeArchives=1, EmbeddedIndexes=1, CollectionView=1 }
export const largeArchives: boolean = !!ViewerFeature.LargeArchives;
export const embeddedIndexes: boolean = !!ViewerFeature.EmbeddedIndexes;
