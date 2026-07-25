<script lang="ts">
	import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { toast } from '$lib/stores/toast.svelte';
	import { sync } from '$lib/stores/sync.svelte';

	interface UserSession {
		id: string;
		client_type: string;
		ip_network: string;
		user_agent: string;
		created_at: number;
		last_active_at: number;
		is_current?: boolean;
	}

	interface UserProfile {
		user_id: string;
		username: string;
		role: string;
	}

	let user = $state<UserProfile | null>(null);
	let sessions = $state<UserSession[]>([]);
	let isLoading = $state(true);

	onMount(async () => {
		try {
			const res = await fetch('/api/v1/auth/me');
			if (!res.ok) {
				goto('/login');
				return;
			}
			user = await res.json();
			await loadSessions();
		} catch (err) {
			goto('/login');
		} finally {
			isLoading = false;
		}
	});

	async function loadSessions() {
		try {
			const res = await fetch('/api/v1/auth/sessions');
			if (res.ok) {
				const data = await res.json();
				sessions = data.sessions || [];
			}
		} catch (_) {}
	}

	async function revokeSession(id: string) {
		try {
			const res = await fetch(`/api/v1/auth/sessions/${id}`, { method: 'DELETE' });
			if (res.ok) {
				sessions = sessions.filter((s) => s.id !== id);
				toast.success('Session revoked.');
			}
		} catch (_) {
			toast.error('Failed to revoke session.');
		}
	}

	async function handleLogout() {
		try {
			await fetch('/api/v1/auth/logout', { method: 'POST' });
		} catch (_) {}
		sync.disable();
		toast.success('Signed out successfully.');
		goto('/login');
	}

	function formatDate(timestampMs: number): string {
		if (!timestampMs) return 'Unknown';
		return new Date(timestampMs).toLocaleString(undefined, {
			dateStyle: 'medium',
			timeStyle: 'short'
		});
	}
</script>

<svelte:head>
	<title>Account Management — KoalaCast</title>
	<meta name="description" content="Manage your KoalaCast user account, active device sessions, and cloud synchronization settings." />
</svelte:head>

<div class="account-page">
	<header class="page-header">
		<h1><i class="ph ph-user-gear" aria-hidden="true"></i> Account Overview</h1>
		<p class="subtitle">Manage your account profile, device sessions, and cloud synchronization.</p>
	</header>

	{#if isLoading}
		<div class="loading-state">
			<i class="ph ph-spinner spinner" aria-hidden="true"></i> Loading account profile...
		</div>
	{:else if user}
		<div class="account-grid">
			<!-- User Profile Card -->
			<section class="card profile-card">
				<div class="card-header">
					<div class="user-avatar">
						<i class="ph ph-user" aria-hidden="true"></i>
					</div>
					<div class="user-meta">
						<h2>{user.username}</h2>
						<span class="role-badge" class:admin={user.role === 'admin'}>
							{user.role}
						</span>
					</div>
				</div>

				<div class="profile-details">
					<div class="detail-item">
						<span class="detail-label"><i class="ph ph-arrows-clockwise" aria-hidden="true"></i> Cloud Sync</span>
						<span class="detail-value active"><i class="ph ph-check-circle" aria-hidden="true"></i> Active</span>
					</div>
					<div class="detail-item">
						<span class="detail-label"><i class="ph ph-fingerprint" aria-hidden="true"></i> Account ID</span>
						<span class="detail-value mono">{user.user_id}</span>
					</div>
				</div>

				<div class="card-actions">
					{#if user.role === 'admin'}
						<a href="/admin" class="btn btn-secondary">
							<i class="ph ph-shield" aria-hidden="true"></i> Admin Dashboard
						</a>
					{/if}
					<button type="button" class="btn btn-danger" onclick={handleLogout}>
						<i class="ph ph-sign-out" aria-hidden="true"></i> Sign Out
					</button>
				</div>
			</section>

			<!-- Active Sessions Card -->
			<section class="card sessions-card">
				<header class="section-header">
					<h3><i class="ph ph-devices" aria-hidden="true"></i> Active Device Sessions</h3>
					<span class="session-count">{sessions.length} {sessions.length === 1 ? 'device' : 'devices'}</span>
				</header>

				{#if sessions.length === 0}
					<p class="empty-text">No other active sessions detected.</p>
				{:else}
					<div class="sessions-list">
						{#each sessions as session (session.id)}
							<div class="session-row">
								<div class="session-icon">
									<i class="ph ph-desktop-tower" aria-hidden="true"></i>
								</div>
								<div class="session-info">
									<div class="session-title">
										<span class="client-type">{session.client_type || 'Browser'}</span>
										{#if session.is_current}
											<span class="current-badge">Current Device</span>
										{/if}
									</div>
									<div class="session-meta">
										<span>IP Subnet: {session.ip_network || 'Direct'}</span>
										<span>•</span>
										<span>Last active: {formatDate(session.last_active_at)}</span>
									</div>
								</div>
								{#if !session.is_current}
									<button
										type="button"
										class="btn-revoke"
										onclick={() => revokeSession(session.id)}
										title="Revoke session"
									>
										Revoke
									</button>
								{/if}
							</div>
						{/each}
					</div>
				{/if}
			</section>
		</div>
	{/if}
</div>

<style>
	.account-page {
		max-width: 900px;
		margin: 0 auto;
		display: flex;
		flex-direction: column;
		gap: 2rem;
	}

	.page-header h1 {
		font-size: 2rem;
		font-weight: 800;
		color: var(--text-primary);
		display: flex;
		align-items: center;
		gap: 0.6rem;
	}

	.subtitle {
		color: var(--text-secondary);
		margin-top: 0.25rem;
		font-size: 0.95rem;
	}

	.loading-state {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		font-size: 1.05rem;
		color: var(--text-secondary);
		padding: 3rem 0;
	}

	.account-grid {
		display: grid;
		grid-template-columns: 1fr 1.5fr;
		gap: 1.5rem;

		@media (max-width: 768px) {
			grid-template-columns: 1fr;
		}
	}

	.card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 20px;
		padding: 1.75rem;
		box-shadow: 0 8px 24px rgba(0, 0, 0, 0.15);
		display: flex;
		flex-direction: column;
		gap: 1.5rem;
	}

	.card-header {
		display: flex;
		align-items: center;
		gap: 1rem;
	}

	.user-avatar {
		width: 3.5rem;
		height: 3.5rem;
		border-radius: 50%;
		background: color-mix(in srgb, var(--accent-green) 20%, transparent);
		color: var(--accent-green);
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 1.8rem;
	}

	.user-meta h2 {
		font-size: 1.35rem;
		font-weight: 800;
		color: var(--text-primary);
		margin: 0;
	}

	.role-badge {
		display: inline-block;
		margin-top: 0.25rem;
		padding: 0.2rem 0.6rem;
		border-radius: 6px;
		font-size: 0.75rem;
		font-weight: 700;
		text-transform: uppercase;
		background: var(--bg-elevated);
		color: var(--text-secondary);
		border: 1px solid var(--border-subtle);
	}

	.role-badge.admin {
		background: color-mix(in srgb, #eab308 20%, transparent);
		color: #facc15;
		border-color: color-mix(in srgb, #eab308 40%, transparent);
	}

	.profile-details {
		display: flex;
		flex-direction: column;
		gap: 0.85rem;
		border-top: 1px solid var(--border-subtle);
		border-bottom: 1px solid var(--border-subtle);
		padding: 1.25rem 0;
	}

	.detail-item {
		display: flex;
		align-items: center;
		justify-content: space-between;
		font-size: 0.92rem;
	}

	.detail-label {
		color: var(--text-secondary);
		display: flex;
		align-items: center;
		gap: 0.4rem;
	}

	.detail-value {
		font-weight: 600;
		color: var(--text-primary);
	}

	.detail-value.active {
		color: var(--accent-green);
		display: flex;
		align-items: center;
		gap: 0.35rem;
	}

	.detail-value.mono {
		font-family: monospace;
		font-size: 0.8rem;
		color: var(--text-muted);
	}

	.card-actions {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	.btn {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		gap: 0.5rem;
		padding: 0.75rem 1.25rem;
		border-radius: 12px;
		font-weight: 700;
		font-size: 0.92rem;
		cursor: pointer;
		border: none;
		text-decoration: none;
		transition: all 0.2s ease;
	}

	.btn-secondary {
		background: var(--bg-elevated);
		color: var(--text-primary);
		border: 1px solid var(--border-subtle);
	}

	.btn-secondary:hover {
		background: color-mix(in srgb, var(--accent-green) 15%, transparent);
		border-color: var(--accent-green);
		color: var(--accent-green);
	}

	.btn-danger {
		background: color-mix(in srgb, #ef4444 15%, transparent);
		color: #f87171;
		border: 1px solid color-mix(in srgb, #ef4444 30%, transparent);
	}

	.btn-danger:hover {
		background: #ef4444;
		color: #ffffff;
	}

	.section-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
	}

	.section-header h3 {
		font-size: 1.15rem;
		font-weight: 700;
		color: var(--text-primary);
		display: flex;
		align-items: center;
		gap: 0.5rem;
		margin: 0;
	}

	.session-count {
		font-size: 0.85rem;
		color: var(--text-muted);
		background: var(--bg-elevated);
		padding: 0.2rem 0.6rem;
		border-radius: 20px;
	}

	.empty-text {
		color: var(--text-muted);
		font-size: 0.9rem;
	}

	.sessions-list {
		display: flex;
		flex-direction: column;
		gap: 0.85rem;
	}

	.session-row {
		display: flex;
		align-items: center;
		gap: 0.85rem;
		padding: 0.85rem;
		border-radius: 12px;
		background: var(--bg-elevated);
		border: 1px solid var(--border-subtle);
	}

	.session-icon {
		font-size: 1.4rem;
		color: var(--accent-green);
	}

	.session-info {
		flex: 1;
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
	}

	.session-title {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		font-weight: 700;
		font-size: 0.92rem;
		color: var(--text-primary);
	}

	.current-badge {
		font-size: 0.72rem;
		padding: 0.15rem 0.5rem;
		border-radius: 6px;
		background: color-mix(in srgb, var(--accent-green) 20%, transparent);
		color: var(--accent-green);
		font-weight: 700;
	}

	.session-meta {
		font-size: 0.8rem;
		color: var(--text-muted);
		display: flex;
		align-items: center;
		gap: 0.4rem;
	}

	.btn-revoke {
		background: transparent;
		border: 1px solid var(--border-subtle);
		color: var(--text-secondary);
		padding: 0.35rem 0.75rem;
		border-radius: 8px;
		font-size: 0.8rem;
		font-weight: 600;
		cursor: pointer;
		transition: all 0.2s ease;
	}

	.btn-revoke:hover {
		background: #ef4444;
		color: #ffffff;
		border-color: #ef4444;
	}

	.spinner {
		animation: spin 1s linear infinite;
	}

	@keyframes spin {
		100% {
			transform: rotate(360deg);
		}
	}
</style>
