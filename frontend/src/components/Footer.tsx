export function Footer() {
  return (
    <footer className="app-footer">
      <div className="app-footer-inner">
        <span className="app-footer-brand">
          <span className="navbar-logo">◆</span>
          Prepwise
        </span>
        <span className="app-footer-note">
          Your LLM API key is used directly from your browser — never stored on our server.
        </span>
        <span className="app-footer-copy">© {new Date().getFullYear()} Prepwise</span>
      </div>
    </footer>
  );
}
