import { Hailbell, HailbellPush, useHailbellUnread } from '@hailbell/react-native';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useState } from 'react';
import { Alert, Platform, Pressable, ScrollView, StyleSheet, Switch, Text, View } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';

import { configureHailbell, isEnabled, registerForPush, showTestCampaign } from './src/hailbell';

configureHailbell();

export default function App() {
  const unread = useHailbellUnread();
  const [status, setStatus] = useState('');
  const [marketing, setMarketing] = useState(false);
  const [dark, setDark] = useState(false);
  const [canNotify, setCanNotify] = useState<boolean | null>(null);

  useEffect(() => {
    HailbellPush.canNotify().then(setCanNotify);
  }, []);

  const openChat = (source: string) => {
    Hailbell.track('chat_opened', { source });
    Hailbell.open();
  };

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.screen}>
        <ScrollView contentContainerStyle={styles.content}>
          <Text style={styles.title}>Hailbell example</Text>
          {!isEnabled && (
            <Text style={styles.warning}>
              Set APP_ID in src/hailbell.ts and appId in app.json to your platform's App ID, then rebuild.
            </Text>
          )}

          <Section title="Chat">
            <Row label="Chat with us" onPress={() => openChat('profile_row')} badge={unread} />
            <Row label="Sign in as a test user" onPress={() => {
              Hailbell.identify({ userId: 'user_123', name: 'Test User', email: 'test@example.com' });
              setStatus('Identified (no identity token, so the chat stays tied to this install).');
            }} />
            <Row label="Send a signal" onPress={() => Hailbell.track('paywall_decline', { placement: 'onboarding' })} />
            <Row label="Sign out (reset)" onPress={() => Hailbell.reset()} />
            <Row label="Delete my data" onPress={async () => {
              try {
                await Hailbell.deleteData();
                setStatus('Chat data deleted.');
              } catch (error) {
                Alert.alert("Couldn't delete", String(error));
              }
            }} />
            <Toggle label="Dark chat" value={dark} onChange={(v) => {
              setDark(v);
              Hailbell.darkMode = v ? true : null;
            }} />
          </Section>

          <Section title="Push">
            <Text style={styles.note}>Notifications allowed: {canNotify === null ? '…' : canNotify ? 'yes' : 'no'}</Text>
            <Row label="Allow notifications and send the token" onPress={async () => {
              setStatus(await registerForPush());
              setCanNotify(await HailbellPush.canNotify());
            }} />
            <Toggle label="Offers and updates" value={marketing} onChange={(v) => {
              setMarketing(v);
              HailbellPush.setMarketing(v);
            }} />
            <Row label="Tag: favourite = thai" onPress={() => HailbellPush.setTags({ favourite: 'thai' })} />
            {Platform.OS === 'android' && <Row label="Show a test campaign push" onPress={showTestCampaign} />}
          </Section>

          {status ? <Text style={styles.status}>{status}</Text> : null}
        </ScrollView>
        <StatusBar style="auto" />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{title}</Text>
      <View style={styles.card}>{children}</View>
    </View>
  );
}

function Row({ label, onPress, badge }: { label: string; onPress: () => void; badge?: number }) {
  return (
    <Pressable onPress={onPress} style={({ pressed }) => [styles.row, pressed && styles.pressed]}>
      <Text style={styles.rowText}>{label}</Text>
      {badge ? <View style={styles.dot} accessibilityLabel={`${badge} unread`} /> : null}
    </Pressable>
  );
}

function Toggle({ label, value, onChange }: { label: string; value: boolean; onChange: (v: boolean) => void }) {
  return (
    <View style={styles.row}>
      <Text style={styles.rowText}>{label}</Text>
      <Switch value={value} onValueChange={onChange} />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: '#f2f2f7' },
  content: { padding: 16, gap: 20 },
  title: { fontSize: 28, fontWeight: '700' },
  warning: { color: '#a15c00', backgroundColor: '#fff4e0', padding: 12, borderRadius: 10 },
  section: { gap: 8 },
  sectionTitle: { fontSize: 13, color: '#6c6c70', textTransform: 'uppercase', marginLeft: 12 },
  card: { backgroundColor: 'white', borderRadius: 12, overflow: 'hidden' },
  row: {
    minHeight: 48,
    paddingHorizontal: 16,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#d1d1d6',
  },
  pressed: { backgroundColor: '#e5e5ea' },
  rowText: { fontSize: 17 },
  dot: { width: 10, height: 10, borderRadius: 5, backgroundColor: '#ff3b30' },
  note: { paddingHorizontal: 16, paddingTop: 12, color: '#6c6c70' },
  status: { color: '#3a3a3c', paddingHorizontal: 4 },
});
