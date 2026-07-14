import { AppShell, Badge, Card, PageHeader, StatusNotice } from './components/AppBuilderUI.jsx';

export default function App() {
  return (
    <AppShell>
      <PageHeader eyebrow="React workspace" title="Your app is being created" description="Draft previews refresh as each usable checkpoint is built." actions={<Badge tone="success">Starter kit ready</Badge>} />
      <Card className="building-card">
        <StatusNotice title="Reusable components are ready">The builder can compose forms, cards, loading states, collections, and API calls without starting from scratch.</StatusNotice>
      </Card>
    </AppShell>
  );
}
