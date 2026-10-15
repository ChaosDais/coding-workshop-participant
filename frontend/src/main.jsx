import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.jsx'
import {
  applyFontSize, applyTheme, getFontSize, getTheme,
} from './settings/theme.js'

// Before the first render, so the page never flashes in the wrong theme or text size.
applyTheme(getTheme())
applyFontSize(getFontSize())

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
