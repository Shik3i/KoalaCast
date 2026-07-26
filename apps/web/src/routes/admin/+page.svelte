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
		<section class="card" id="metrics">
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
			<section class="card" id="registration">
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
		<section class="card" id="users">
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
		<section class="card" id="feeds">
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
		gap: 12px;
		padding: 24px 22px 36px;
	}

	.admin-head h2 {
		color: var(--ink-strong);
		font: 800 30px/1 var(--font-ui);
		letter-spacing: -.04em;
		display: flex;
		align-items: center;
		gap: 9px;
	}
	.admin-head h2 :global(.ph-fill) { color: var(--accent-ink); }
	.admin-sub { color: var(--ink-4); font: 600 9px/1.5 var(--font-mono); letter-spacing: .06em; margin-top: 7px; text-transform: uppercase; }

	.card {
		background: var(--bg-sunken);
		border: 1px solid var(--border-hair);
		border-radius: 8px;
		padding: 18px;
	}
	.card h3 { color: var(--ink-2); font: 700 17px/1.2 var(--font-ui); letter-spacing: -.02em; }

	.metrics-grid {
		display: grid;
		grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
		gap: 10px;
		margin-top: 14px;
	}

	.metric-box {
		background: var(--bg-panel);
		border: 1px solid var(--border-hair);
		padding: 14px;
		border-radius: 6px;
		display: grid;
		gap: 6px;
	}

	.metric-box .val {
		color: var(--ink-strong);
		font: 800 24px/1 var(--font-ui);
		letter-spacing: -.035em;
	}

	.metric-box .lbl {
		color: var(--ink-4);
		font: 600 8px/1 var(--font-mono);
		letter-spacing: .08em;
		text-transform: uppercase;
	}

	/* Let wide tables scroll on their own instead of blowing out the mobile layout. */
	.table-scroll {
		width: 100%;
		overflow-x: auto;
		-webkit-overflow-scrolling: touch;
	}

	.empty-note {
		color: var(--ink-4);
		font-size: 12px;
		margin-top: 12px;
	}

	.admin-table {
		width: 100%;
		border-collapse: collapse;
		margin-top: 14px;
		min-width: 480px;
	}

	.admin-table th, .admin-table td {
		padding: 11px 9px;
		text-align: left;
		border-bottom: 1px solid var(--border-row);
	}
	.admin-table th { color: var(--ink-4); font: 600 8px/1 var(--font-mono); letter-spacing: .08em; text-transform: uppercase; }
	.admin-table td { color: var(--ink-3); font-size: 12px; }
	.admin-table td strong { color: var(--ink-2); font-family: var(--font-ui); }

	.badge {
		padding: 0.2rem 0.5rem;
		border-radius: 4px;
		background: var(--accent-wash);
		color: var(--accent-ink);
		font: 600 8px/1 var(--font-mono);
		text-transform: uppercase;
	}

	.badge.suspended {
		background: color-mix(in srgb, var(--color-danger) 16%, transparent);
		color: var(--color-danger);
	}

	.btn-sm {
		font: 600 8px/1 var(--font-mono);
		padding: 7px 9px;
		border-radius: 4px;
		border: 1px solid var(--border-ui);
		background: transparent;
		color: var(--ink-2);
		text-transform: uppercase;
	}
	.btn-sm:hover { border-color: var(--accent-ink); color: var(--accent-ink); }
	.row-actions { display: flex; gap: 0.4rem; flex-wrap: wrap; }

	.reg-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1rem;
		flex-wrap: wrap;
		margin-top: 12px;
	}
	.reg-info { min-width: 0; }
	.reg-state { color: var(--ink-3); font-size: 12px; }
	.reg-state strong { color: var(--color-danger); }
	.reg-state strong.on { color: var(--accent-green); }
	.reg-hint { font-size: 10px; color: var(--ink-4); margin-top: 5px; }
	.btn-reg {
		flex-shrink: 0;
		background: var(--accent-fill);
		color: var(--accent-on);
		border: none;
		padding: 9px 11px;
		border-radius: 5px;
		font: 700 9px/1 var(--font-mono);
		text-transform: uppercase;
	}
	.btn-reg.danger { background: var(--color-danger); }
	.btn-reg:disabled { opacity: 0.5; cursor: not-allowed; }

	.error-banner {
		padding: 12px;
		border: 1px solid color-mix(in srgb, var(--color-danger) 55%, transparent);
		background: color-mix(in srgb, var(--color-danger) 13%, var(--bg-sunken));
		color: var(--ink);
		border-radius: 6px;
	}
	.loading { color: var(--ink-4); font: 600 10px/1 var(--font-mono); text-transform: uppercase; }
	@media (max-width: 560px) { .admin-page { padding: 16px; }.metrics-grid { grid-template-columns: repeat(2, minmax(0,1fr)); }.card { padding: 14px; } }
</style>
