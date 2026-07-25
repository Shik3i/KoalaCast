<script lang="ts">
	import { t } from '$lib/i18n';
	import { onMount } from 'svelte';
	import { toast } from '$lib/stores/toast.svelte';

	let systemStatus = $state<any>(null);
	let users = $state<any[]>([]);
	let feedHealth = $state<any[]>([]);
	let isLoading = $state(true);
	let errorMsg = $state('');

	onMount(() => {
		loadAdminData();
	});

	async function loadAdminData() {
		isLoading = true;
		errorMsg = '';

		try {
			const [statusRes, usersRes, healthRes] = await Promise.all([
				fetch('/api/v1/admin/status'),
				fetch('/api/v1/admin/users'),
				fetch('/api/v1/admin/feed-health')
			]);

			if (!statusRes.ok || !usersRes.ok || !healthRes.ok) {
				errorMsg = t('admin.accessDenied');
				return;
			}

			systemStatus = await statusRes.json();
			const userData = await usersRes.json();
			users = userData.users || [];
			const healthData = await healthRes.json();
			feedHealth = healthData.feeds || [];
		} catch (err: any) {
			errorMsg = t('admin.metricsNetworkError');
		} finally {
			isLoading = false;
		}
	}

	async function handleToggleSuspend(userId: string, currentSuspended: boolean) {
		try {
			const res = await fetch(`/api/v1/admin/users/${userId}/suspend`, {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ suspend: !currentSuspended })
			});
			if (res.ok) {
				loadAdminData();
			} else {
				const d = await res.json().catch(() => ({}));
				toast.error(d.error || t('toast.userUpdateError'));
			}
		} catch (_) {
			toast.error(t('toast.userUpdateNetworkError'));
		}
	}

	async function handleRevokeSessions(userId: string, username: string) {
		if (!confirm(t('admin.confirmRevokeAll', { username }))) return;
		try {
			const res = await fetch(`/api/v1/admin/users/${userId}/sessions`, { method: 'DELETE' });
			if (res.ok) {
				toast.success(t('toast.sessionsRevokedFor', { username }));
				loadAdminData();
			} else {
				toast.error(t('toast.sessionsRevokeError'));
			}
		} catch (_) {
			toast.error(t('toast.sessionsRevokeNetworkError'));
		}
	}

	async function handleToggleRegistration() {
		if (!systemStatus || systemStatus.registration_locked) return;
		const enable = !systemStatus.registration_enabled;
		try {
			const res = await fetch('/api/v1/admin/registration/toggle', {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ enabled: enable })
			});
			if (res.ok) {
				toast.success(enable ? t('toast.registrationEnabled') : t('toast.registrationDisabled'));
				loadAdminData();
			} else {
				const d = await res.json().catch(() => ({}));
				toast.error(d.error || t('toast.registrationChangeError'));
			}
		} catch (_) {
			toast.error(t('toast.registrationNetworkError'));
		}
	}

	async function handleRefreshFeed(podcastId: string) {
		try {
			const res = await fetch(`/api/v1/admin/podcasts/${podcastId}/refresh`, { method: 'POST' });
			if (res.ok) {
				toast.success(t('toast.feedRefreshRequested'));
				loadAdminData();
			}
		} catch (_) {}
	}
</script>

<div class="admin-page">
	<div class="admin-head">
		<h2><i class="ph-fill ph-shield-star" aria-hidden="true"></i> {t('admin.title')}</h2>
		<p class="admin-sub">{t('admin.subtitle')}</p>
	</div>

	{#if isLoading}
		<div class="loading">{t('admin.loading')}</div>
	{:else if errorMsg}
		<div class="error-banner">{errorMsg}</div>
	{:else}
		<!-- System Metrics -->
		<section class="card">
			<h3>{t('admin.metrics')}</h3>
			{#if systemStatus}
				<div class="metrics-grid">
					<div class="metric-box">
						<span class="val">{systemStatus.user_count}</span>
						<span class="lbl">{t('admin.totalUsers')}</span>
					</div>
					<div class="metric-box">
						<span class="val">{systemStatus.podcast_count}</span>
						<span class="lbl">{t('admin.podcasts')}</span>
					</div>
					<div class="metric-box">
						<span class="val">{systemStatus.episode_count}</span>
						<span class="lbl">{t('admin.episodes')}</span>
					</div>
					<div class="metric-box">
						<span class="val">{(systemStatus.database_size_bytes / (1024 * 1024)).toFixed(2)} MB</span>
						<span class="lbl">{t('admin.dbSize')}</span>
					</div>
					<div class="metric-box">
						<span class="val">{systemStatus.worker_running ? t('admin.workerActive') : t('admin.workerStopped')}</span>
						<span class="lbl">{t('admin.workerStatus')}</span>
					</div>
				</div>
			{/if}
		</section>

		<!-- Public Registration Control -->
		{#if systemStatus}
			<section class="card">
				<h3>{t('admin.publicRegistration')}</h3>
				<div class="reg-row">
					<div class="reg-info">
						<p class="reg-state">
							{t('admin.signupsAre')}
							<strong class:on={systemStatus.registration_enabled}>
								{systemStatus.registration_enabled ? t('admin.enabled') : t('admin.disabled')}
							</strong>.
						</p>
						{#if systemStatus.registration_locked}
							<p class="reg-hint">
								{@html t('admin.lockedByEnv')}
							</p>
						{/if}
					</div>
					<button
						class="btn-reg"
						class:danger={systemStatus.registration_enabled}
						disabled={systemStatus.registration_locked}
						onclick={handleToggleRegistration}
					>
						{systemStatus.registration_enabled ? t('admin.disableRegistration') : t('admin.enableRegistration')}
					</button>
				</div>
			</section>
		{/if}

		<!-- Users Table -->
		<section class="card">
			<h3>{t('admin.registeredUsers', { count: users.length })}</h3>
			{#if users.length === 0}
				<p class="empty-note">{t('admin.noUsers')}</p>
			{:else}
			<div class="table-scroll">
			<table class="admin-table">
				<thead>
					<tr>
						<th>{t('admin.username')}</th>
						<th>{t('admin.role')}</th>
						<th>{t('admin.activeSessions')}</th>
						<th>{t('admin.status')}</th>
						<th>{t('admin.actions')}</th>
					</tr>
				</thead>
				<tbody>
					{#each users as user}
						<tr>
							<td><strong>{user.username}</strong></td>
							<td>{user.role}</td>
							<td>{user.active_sessions}</td>
							<td>
								<span class="badge" class:suspended={user.is_suspended}>
									{user.is_suspended ? t('admin.suspended') : t('admin.userActive')}
								</span>
							</td>
							<td>
								<div class="row-actions">
									<button class="btn-sm" onclick={() => handleToggleSuspend(user.id, user.is_suspended)}>
										{user.is_suspended ? t('admin.restore') : t('admin.suspend')}
									</button>
									{#if user.active_sessions > 0}
										<button class="btn-sm" onclick={() => handleRevokeSessions(user.id, user.username)}>
											{t('common.signOut')}
										</button>
									{/if}
								</div>
							</td>
						</tr>
					{/each}
				</tbody>
			</table>
			</div>
			{/if}
		</section>

		<!-- Feed Health Table -->
		<section class="card">
			<h3>{t('admin.feedHealth')}</h3>
			{#if feedHealth.length === 0}
				<p class="empty-note">{t('admin.noFeeds')}</p>
			{:else}
			<div class="table-scroll">
			<table class="admin-table">
				<thead>
					<tr>
						<th>{t('admin.podcastTitle')}</th>
						<th>{t('admin.errors')}</th>
						<th>{t('admin.category')}</th>
						<th>{t('admin.httpStatus')}</th>
						<th>{t('admin.actions')}</th>
					</tr>
				</thead>
				<tbody>
					{#each feedHealth as feed}
						<tr>
							<td>{feed.title || feed.feed_url}</td>
							<td>{feed.consecutive_errors}</td>
							<td>{feed.last_error_category || 'OK'}</td>
							<td>{feed.last_http_status || '-'}</td>
							<td>
								<button class="btn-sm" onclick={() => handleRefreshFeed(feed.id)}>{t('admin.refresh')}</button>
							</td>
						</tr>
					{/each}
				</tbody>
			</table>
			</div>
			{/if}
		</section>
	{/if}
</div>

<style>
	.admin-page {
		display: flex;
		flex-direction: column;
		gap: 1.5rem;
	}

	.admin-head h2 {
		font-size: clamp(1.6rem, 3vw, 2.1rem);
		font-weight: 800;
		letter-spacing: -0.02em;
		display: flex;
		align-items: center;
		gap: 0.55rem;
	}
	.admin-head h2 :global(.ph-fill) { color: var(--accent-green); }
	.admin-sub { color: var(--text-muted); font-size: 0.95rem; margin-top: 0.25rem; }

	.card {
		background: var(--bg-surface);
		border: 1px solid var(--border-subtle);
		border-radius: 14px;
		padding: 1.5rem;
	}

	.metrics-grid {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
		gap: 1rem;
		margin-top: 1rem;
	}

	.metric-box {
		background: var(--bg-primary);
		padding: 1rem;
		border-radius: 6px;
		display: flex;
		flex-direction: column;
		align-items: center;
	}

	.metric-box .val {
		font-size: 1.5rem;
		font-weight: 700;
		color: var(--accent-green);
	}

	.metric-box .lbl {
		font-size: 0.85rem;
		color: var(--text-secondary);
	}

	/* Let wide tables scroll on their own instead of blowing out the mobile layout. */
	.table-scroll {
		width: 100%;
		overflow-x: auto;
		-webkit-overflow-scrolling: touch;
	}

	.empty-note {
		color: var(--text-muted);
		font-size: 0.9rem;
		margin-top: 0.75rem;
	}

	.admin-table {
		width: 100%;
		border-collapse: collapse;
		margin-top: 1rem;
		min-width: 480px;
	}

	.admin-table th, .admin-table td {
		padding: 0.75rem;
		text-align: left;
		border-bottom: 1px solid var(--border-subtle);
	}

	.badge {
		padding: 0.2rem 0.5rem;
		border-radius: 4px;
		background: var(--accent-light);
		color: var(--accent-green-hover);
		font-size: 0.8rem;
		font-weight: 600;
	}

	.badge.suspended {
		background: #f8d7da;
		color: #721c24;
	}

	.btn-sm {
		font-size: 0.8rem;
		padding: 0.3rem 0.6rem;
		border-radius: 4px;
		border: 1px solid var(--border-subtle);
		background: var(--bg-primary);
		color: var(--text-primary);
	}
	.btn-sm:hover { border-color: var(--accent-green); }
	.row-actions { display: flex; gap: 0.4rem; flex-wrap: wrap; }

	.reg-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1rem;
		flex-wrap: wrap;
		margin-top: 0.75rem;
	}
	.reg-info { min-width: 0; }
	.reg-state { font-size: 0.95rem; }
	.reg-state strong { color: var(--color-danger); }
	.reg-state strong.on { color: var(--accent-green); }
	.reg-hint { font-size: 0.82rem; color: var(--text-muted); margin-top: 0.25rem; }
	.reg-hint code {
		font-family: var(--font-mono, monospace);
		background: var(--bg-elevated);
		padding: 0.05rem 0.35rem;
		border-radius: 4px;
	}
	.btn-reg {
		flex-shrink: 0;
		background: var(--accent-green);
		color: #fff;
		border: none;
		padding: 0.55rem 1.1rem;
		border-radius: 8px;
		font-weight: 700;
		font-size: 0.88rem;
	}
	.btn-reg.danger { background: var(--color-danger); }
	.btn-reg:disabled { opacity: 0.5; cursor: not-allowed; }

	.error-banner {
		padding: 1rem;
		background: #f8d7da;
		color: #721c24;
		border-radius: 6px;
	}
</style>
