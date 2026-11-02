// Appended only in the temporary source build. Uses upstream bundle functions.
gulp.task("document-parts-bundle", function () {
  // Upstream normally randomizes this build constant. Reuse the exact 6.5.438
  // npm distribution value, preserving its event semantics and reproducibility.
  const defines = { ...DEFINES, GENERIC: true,
    INTERNAL_EVT: "0a475cb6-573c-46ed-869d-fa7c5839d995" };
  return ordered([
    createMainBundle(defines).pipe(gulp.dest("build/document-parts/")),
    createWorkerBundle(defines).pipe(gulp.dest("build/document-parts/")),
  ]);
});
