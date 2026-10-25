// Old embedded browsers (e.g. LG signage, Chrome 38) cannot run this page; use the legacy one.
(function () {
  var modern = false;
  try { modern = !!(window.fetch && window.Promise && window.URLSearchParams && window.CSS && CSS.supports && CSS.supports('display', 'grid')); } catch (e) {}
  if (!modern) location.replace('../legacy/' + location.search);
})();
