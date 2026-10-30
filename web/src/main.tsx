import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { SoundSeekApp } from './SoundSeekApp'
import './styles.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <SoundSeekApp />
  </StrictMode>,
)
