/** Compile-time profile: shared code, optional implementations omitted by bundling. */
export const enum ViewerFeature { LargeArchives=0, EmbeddedIndexes=0, CollectionView=0 }
export const largeArchives: boolean = !!ViewerFeature.LargeArchives;
export const embeddedIndexes: boolean = !!ViewerFeature.EmbeddedIndexes;
