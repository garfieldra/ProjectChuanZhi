import type { RefObject } from 'react'
import { AuthDialog } from './AuthDialog'
import { AccountMenu } from './AccountMenu'
import type { AuthSession } from './auth'
export function AccountDialog({ session, onClose, onSessionChange, returnFocusRef }: { session: AuthSession | null; onClose: () => void; onSessionChange: (session: AuthSession, reason: 'profile' | 'logout' | 'login') => void; returnFocusRef?: RefObject<HTMLElement> }) {
  return session?.authenticated ? <AccountMenu session={session} onClose={onClose} onSessionChange={onSessionChange} returnFocusRef={returnFocusRef} /> : <AuthDialog session={session} onClose={onClose} onSessionChange={value => onSessionChange(value, 'login')} returnFocusRef={returnFocusRef} />
}
