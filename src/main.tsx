import { createRoot } from "react-dom/client";
import { App } from "./App";
import { DialogProvider } from "./dialogs";
import "./styles.css";

createRoot(document.getElementById("root")!).render(
  <DialogProvider>
    <App />
  </DialogProvider>,
);
