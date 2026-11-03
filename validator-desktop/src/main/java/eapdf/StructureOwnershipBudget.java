package eapdf;

import static eapdf.EaPdfChecks.*;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;

/** The web engine's bounded traversal for repeated structure-to-DPart ownership assessments. */
final class StructureOwnershipBudget {
  private final PDDocument document;
  private final Map<COSDictionary, PageContentChecks.Page> pages;
  private final Map<COSDictionary, Set<COSDictionary>> scopes;
  private final Map<PageContentChecks.Key, Set<COSDictionary>> locations = new HashMap<>();
  private final Map<COSDictionary, Set<COSDictionary>> objects = new IdentityHashMap<>();
  private final Map<COSDictionary, Set<COSDictionary>> streams = new IdentityHashMap<>();
  private final Map<COSDictionary, Set<String>> owners = new IdentityHashMap<>(), types = new IdentityHashMap<>(), subtypes = new IdentityHashMap<>();
  private record Reference(COSDictionary element, int child, COSBase page) {}
  private final Set<Reference> assessed = new HashSet<>();
  private long work;
  private final long workLimit;
  private static final class Limit extends RuntimeException { private static final long serialVersionUID = 1L; }
  private record Ancestry(Set<String> guids, boolean unknown) {}

  StructureOwnershipBudget(PDDocument document, Map<COSDictionary, PageContentChecks.Page> pages,
                           Map<COSDictionary, Set<COSDictionary>> scopes) {
    this.document = document; this.pages = pages; this.scopes = scopes;
    this.workLimit = Math.min(2_000_000_000L, Math.max(100000L, (long) document.getNumberOfPages() * 20000L));
  }
  private void tick(int depth, int items) { work += items; if (Thread.currentThread().isInterrupted()) throw new Limit(); }
  private void tick() { tick(0, 1); }

  boolean unavailable(List<COSDictionary> elements) throws IOException {
    try {
      prepareParts(); preparePages();
      for (COSDictionary element : elements) {
        COSBase page = null;
        Set<COSDictionary> ancestors = identitySet();
        for (COSDictionary parent = element; parent != null && page == null; parent = dict(parent, "P")) {
          tick(ancestors.size(), 1);
          if (!ancestors.add(parent)) throw new Limit();
          page = get(parent, "Pg");
        }
        walk(element, page, identitySet(), 0);
      }
      return false;
    } catch (Limit unavailable) { return true; }
  }

  private void prepareParts() {
    var pageIndex = new AttachmentChecks.PageIndex(document);
    COSBase rawRoot = get(document.getDocumentCatalog().getCOSObject(), "DPartRoot");
    COSBase rawNode = rawRoot instanceof COSDictionary root ? get(root, "DPartRootNode") : null;
    var pending = new ArrayDeque<COSDictionary>();
    if (rawNode instanceof COSDictionary node) pending.add(node);
    Map<COSDictionary, Ancestry> ancestry = new IdentityHashMap<>();
    Set<COSDictionary> seen = identitySet();
    while (!pending.isEmpty()) {
      COSDictionary node = pending.removeFirst(); tick();
      if (!seen.add(node)) throw new Limit();
      var children = PageContentChecks.dictionaries(get(node, "DParts"));
      var inherited = ancestry.getOrDefault(dict(node, "Parent"), new Ancestry(Set.of(), false));
      Set<String> guids = new HashSet<>(inherited.guids()); boolean unknown = inherited.unknown();
      COSDictionary dpm = dict(node, "DPM"); COSBase value = get(dpm, "Mail_GUID");
      if (!children.isEmpty() && value != null) {
        tick(0, guids.size());
        if (value instanceof COSString && decodedString(value) != null && !string(value).isEmpty()) guids.add(string(value));
        else unknown = true;
      }
      ancestry.put(node, new Ancestry(guids, unknown));
      if (!children.isEmpty()) { pending.addAll(children); continue; }
      var span = pageIndex.range(node);
      if (span.isEmpty()) throw new Limit();
      for (var page : span) {
        tick(); COSDictionary object = page.getCOSObject();
        if (guids.size() == 1 && !unknown) owners.computeIfAbsent(object, ignored -> new HashSet<>()).addAll(guids);
        addName(types, object, get(dpm, "Mail_ContentSetType"));
        addName(subtypes, object, get(dpm, "Mail_Subtype"));
      }
    }
  }
  private static void addName(Map<COSDictionary, Set<String>> map, COSDictionary page, COSBase value) {
    if (value instanceof COSName name) {
      String text = DocumentPartChecks.utf8Name(name);
      if (text != null) map.computeIfAbsent(page, ignored -> new HashSet<>()).add(text);
    }
  }
  private void preparePages() throws IOException {
    Map<COSDictionary, Set<COSDictionary>> streamScopesByPage = new IdentityHashMap<>();
    for (var scope : scopes.entrySet()) if (scope.getKey() instanceof COSStream)
      for (COSDictionary page : scope.getValue())
        streamScopesByPage.computeIfAbsent(page, ignored -> identitySet()).add(scope.getKey());
    for (var page : document.getPages()) {
      tick(); COSDictionary object = page.getCOSObject();
      COSBase contents = get(object, "Contents");
      List<? extends COSBase> content = contents instanceof COSArray array ? array.toList() : java.util.Collections.singletonList(contents);
      for (COSBase raw : content) {
        tick(); if (resolve(raw) instanceof COSStream stream) streams.computeIfAbsent(stream, ignored -> identitySet()).add(object);
      }
      for (var annotation : page.getAnnotations()) {
        tick(); objects.computeIfAbsent(annotation.getCOSObject(), ignored -> identitySet()).add(object);
      }
      for (COSDictionary scope : streamScopesByPage.getOrDefault(object, Set.of())) {
        tick(); objects.computeIfAbsent(scope, ignored -> identitySet()).add(object);
      }
      for (var sequence : pages.get(object).sequences) {
        tick(); if (sequence.key != null) locations.computeIfAbsent(sequence.key, ignored -> identitySet()).add(object);
      }
    }
  }
  private void walk(COSDictionary node, COSBase inheritedPage, Set<COSDictionary> seen, int depth) {
    tick(depth, 1); if (!seen.add(node)) throw new Limit();
    COSBase page = get(node, "Pg") == null ? inheritedPage : get(node, "Pg");
    COSBase rawKids = get(node, "K");
    List<? extends COSBase> kids = rawKids instanceof COSArray array ? array.toList() : java.util.Collections.singletonList(rawKids);
    int childIndex = 0;
    for (COSBase raw : kids) {
      int index = childIndex++;
      tick(depth, 1); COSBase child = resolve(raw);
      if (child instanceof COSDictionary element && get(element, "S") != null) { walk(element, page, seen, depth + 1); continue; }
      // The same reference is revisited through its enclosing message, group and content set.
      // Its page context is part of the key; traversal/cycle checks still run on every path.
      if (!assessed.add(new Reference(node, index, page))) continue;
      COSBase declared = page, target = null, mcid = null;
      Set<COSDictionary> choices = identitySet(); boolean objectReference = false;
      if (child instanceof COSInteger) { target = page; mcid = child; }
      else if (child instanceof COSDictionary dictionary && "MCR".equals(name(get(dictionary, "Type")))) {
        mcid = get(dictionary, "MCID"); declared = get(dictionary, "Pg") == null ? page : get(dictionary, "Pg");
        target = get(dictionary, "Stm") == null ? declared : get(dictionary, "Stm");
      } else if (child instanceof COSDictionary dictionary && "OBJR".equals(name(get(dictionary, "Type")))) {
        declared = get(dictionary, "Pg") == null ? page : get(dictionary, "Pg"); objectReference = true;
        if (get(dictionary, "Obj") instanceof COSDictionary object) choices.addAll(objects.getOrDefault(object, Set.of()));
      } else continue;
      if (target != null) {
        if (!(mcid instanceof COSInteger integer) || integer.longValue() < 0 || integer.longValue() > Integer.MAX_VALUE) continue;
        if (target instanceof COSDictionary scope) {
          Set<COSDictionary> candidates = streams.getOrDefault(scope, Set.of(scope));
          for (COSDictionary candidate : candidates) {
            tick(); var connected = locations.getOrDefault(new PageContentChecks.Key(candidate, integer.intValue()), Set.of());
            tick(0, connected.size()); choices.addAll(connected);
          }
        } else tick();
      } else if (!objectReference) continue;
      if (declared != null) {
        if (declared instanceof COSDictionary dictionary && choices.contains(dictionary)) choices = new HashSet<>(Set.of(dictionary));
        else choices.clear();
      }
      for (COSDictionary choice : choices) {
        tick(); tick(0, owners.getOrDefault(choice, Set.of()).size());
        tick(0, types.getOrDefault(choice, Set.of()).size()); tick(0, subtypes.getOrDefault(choice, Set.of()).size());
      }
    }
  }
}
