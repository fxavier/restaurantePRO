// keycloak.v2 labels the read-only attempted username (the code and password pages) with for="username", while the
// field's id is kc-attempted-username: point the label at the field, so it has an accessible name (WCAG 1.3.1, 4.1.2).
// The theme's scripts load in <head>, so this waits for the form.
document.addEventListener("DOMContentLoaded", () => {
  const attempted = document.getElementById("kc-attempted-username");
  const label = document.querySelector('label[for="username"]');
  if (attempted && label) label.htmlFor = attempted.id;
});
