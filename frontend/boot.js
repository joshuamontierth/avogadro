// Picks the layout before the first paint, so a results URL never flashes the home page.
(function () {
  var p = new URLSearchParams(location.search);
  var c = document.documentElement.classList;
  c.add((p.get("q") || "").trim() ? "is-results" : "is-home");
  if (p.get("c") === "images") c.add("cat-images");
})();
