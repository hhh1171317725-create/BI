import { headers } from 'next/headers';
import { redirect, notFound } from 'next/navigation';
import { userFromRequest } from '@/lib/security';
import Workspace from '@/components/workspace';
export const dynamic = 'force-dynamic';
export default async function Page({ params }: { params: Promise<{ page?: string[] }> }) {
  const { page } = await params,
    screen = '/' + (page || []).join('/');
  if (
    !['/', '/login', '/sleep', '/fitness', '/assistant', '/settings', '/settings/sync'].includes(
      screen,
    )
  )
    notFound();
  const h = await headers();
  let authenticated = false;
  try {
    await userFromRequest(
      new Request((process.env.APP_ORIGIN || 'http://localhost') + '/api/settings', {
        headers: { cookie: h.get('cookie') || '' },
      }),
    );
    authenticated = true;
  } catch {}
  if (!authenticated && screen !== '/login') redirect('/login');
  if (authenticated && screen === '/login') redirect('/');
  return <Workspace screen={screen} />;
}
