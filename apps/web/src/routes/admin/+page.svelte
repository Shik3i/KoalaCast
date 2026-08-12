<script lang="ts">
	import { t } from '$lib/i18n';
	import { confirmDialog } from '$lib/stores/confirm.svelte';
	import { onMount } from 'svelte';
	import { toast } from '$lib/stores/toast.svelte';

	let systemStatus = $state<any>(null);
	let users = $state<any[]>([]);
	let feedHealth = $state<any[]>([]);
	let apiErrors = $state<any[]>([]);
	let isLoading = $state(true);
	let errorMsg = $state('');
	let lastRefreshedAt = $state(0);
	let userQuery = $state('');
	let feedQuery = $state('');
	let errorQuery = $state('');
	// The variable name is rendered as a real <code> element rather than as markup
	// carried inside the translation catalogue; see the same change in the Inbox.
	const lockedHintParts = $derived(t('admin.lockedByEnv').split('{variable}'));
	const filteredUsers = $derived(users.filter((user) => user.username.toLowerCase().includes(userQuery.trim().toLowerCase())));
	const filteredFeeds = $derived(feedHealth.filter((feed) => `${feed.title || ''} ${feed.feed_url || ''}`.toLowerCase().includes(feedQuery.trim().toLowerCase())));
	const filteredErrors = $derived(apiErrors.filter((entry) =>
		`${entry.status_code} ${entry.method} ${entry.path} ${entry.message} ${entry.request_id} ${entry.user_id}`
			.toLowerCase()
			.includes(errorQuery.trim().toLowerCase())
	));

	onMount(() => {
		loadAdminData();
	});

	async function loadAdminData() {
		isLoading = true;
		errorMsg = '';

		try {
			const [statusRes, usersRes, healthRes, errorsRes] = await Promise.all([
				fetch('/api/v1/admin/status'),
				fetch('/api/v1/admin/users'),
				fetch('/api/v1/admin/feed-health'),
				fetch('/api/v1/admin/errors?limit=250')
			]);

			if (!statusRes.ok || !usersRes.ok || !healthRes.ok || !errorsRes.ok) {
				const statuses = [statusRes.status, usersRes.status, healthRes.status, errorsRes.status];
				errorMsg = statuses.some((status) => status === 401 || status === 403)
					? t('admin.accessDenied')
					: t('admin.metricsNetworkError');
				return;
			}

			systemStatus = await statusRes.json();
			const userData = await usersRes.json();
			users = userData.users || [];
			const healthData = await healthRes.json();
			feedHealth = healthData.feeds || [];
			const errorsData = await errorsRes.json();
			apiErrors = errorsData.errors || [];
			lastRefreshedAt = Date.now();
		} catch (err: any) {
			errorMsg = t('admin.metricsNetworkError');
		} finally {
			isLoading = false;
		}
	}

	async function handleToggleSuspend(userId: string, currentSuspended: boolean) {
		if (!currentSuspended && !(await confirmDialog.ask(t('admin.confirmSuspend')))) return;
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
		if (!(await confirmDialog.ask(t('admin.confirmRevokeAll', { username })))) return;
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
		<h1><i class="ph-fill ph-shield-star" aria-hidden="true"></i> {t('admin.title')}</h1>
		<p class="admin-sub">{t('admin.subtitle')}</p>
		<div class="admin-refresh">
			<span>{lastRefreshedAt ? new Date(lastRefreshedAt).toLocaleTimeString() : '—'}</span>
			<button onclick={loadAdminData} disabled={isLoading}>{t('admin.refresh')}</button>
		</div>
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
						<span class="val">{(systemStatus.episode_payload_bytes / (1024 * 1024)).toFixed(2)} MB</span>
						<span class="lbl">{t('admin.episodePayload')}</span>
					</div>
					<div class="metric-box">
						<span class="val">{systemStatus.notification_feed_count}</span>
						<span class="lbl">{t('admin.notificationFeeds')}</span>
					</div>
					<div class="metric-box">
						<span class="val">{systemStatus.worker_running ? t('admin.workerActive') : t('admin.workerStopped')}</span>
						<span class="lbl">{t('admin.workerStatus')}</span>
					</div>
				</div>
				<p class="metric-note">
					{t('admin.storageExplanation', {
						main: (systemStatus.database_main_size_bytes / (1024 * 1024)).toFixed(2),
						wal: (systemStatus.database_wal_size_bytes / (1024 * 1024)).toFixed(2),
						limit: systemStatus.episode_retention_limit
					})}
				</p>
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
								{lockedHintParts[0]}<code>KC_REGISTRATION_ENABLED</code>{lockedHintParts[1] ?? ''}
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
			<input class="table-search" bind:value={userQuery} type="search" placeholder={t('admin.username')} aria-label={t('admin.username')} />
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
					{#each filteredUsers as user}
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
									<button class="btn-sm" class:danger={!user.is_suspended} onclick={() => handleToggleSuspend(user.id, user.is_suspended)}>
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

		<!-- Persisted API Error Table -->
		<section class="card" id="errors">
			<h3>{t('admin.errors')} · API ({apiErrors.length})</h3>
			<input class="table-search" bind:value={errorQuery} type="search" placeholder="HTTP 400 · /api/v1/…" aria-label={t('admin.errors')} />
			{#if apiErrors.length === 0}
				<p class="empty-note">—</p>
			{:else}
				<div class="table-scroll">
					<table class="admin-table error-table">
						<thead>
							<tr>
								<th>{t('account.lastActive')}</th>
								<th>{t('admin.httpStatus')}</th>
								<th>Request</th>
								<th>{t('admin.errors')}</th>
							</tr>
						</thead>
						<tbody>
							{#each filteredErrors as entry}
								<tr>
									<td class="error-time">{new Date(entry.occurred_at).toLocaleString()}</td>
									<td><span class="error-status">{entry.status_code}</span></td>
									<td><code>{entry.method} {entry.path}</code></td>
									<td>
										<details>
											<summary>{entry.message || `${entry.method} ${entry.path}`}</summary>
											<small>Request-ID: <code>{entry.request_id || '—'}</code></small>
											<small>User-ID: <code>{entry.user_id || '—'}</code></small>
										</details>
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
			<input class="table-search" bind:value={feedQuery} type="search" placeholder={t('admin.podcastTitle')} aria-label={t('admin.podcastTitle')} />
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
					{#each filteredFeeds as feed}
						<tr>
							<td>
								<details>
									<summary>{feed.title || feed.feed_url}</summary>
									<code>{feed.feed_url}</code>
									{#if feed.last_error}<small>{feed.last_error}</small>{/if}
								</details>
							</td>
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

	.admin-head h1 {
		color: var(--ink-strong);
		font: 800 30px/1 var(--font-ui);
		letter-spacing: -.04em;
		display: flex;
		align-items: center;
		gap: 9px;
	}
	.admin-head h1 :global(.ph-fill) { color: var(--accent-ink); }
	.admin-sub { color: var(--ink-4); font: 600 11px/1.5 var(--font-mono); letter-spacing: .01em; margin-top: 7px; }
	.admin-refresh { display: flex; align-items: center; gap: 8px; margin-top: 10px; color: var(--ink-4); font: 600 10px/1 var(--font-mono); }
	.admin-refresh button, .table-search { min-height: 44px; border: 1px solid var(--border-ui); border-radius: var(--radius-control); background: var(--bg-panel); color: var(--ink-2); }
	.admin-refresh button { padding: 0 12px; font: inherit; text-transform: uppercase; }
	.table-search { width: min(360px, 100%); margin-top: 12px; padding: 0 12px; }

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
		font: 600 10px/1 var(--font-mono);
		letter-spacing: .01em;

	}
	.metric-note {
		margin-top: 12px;
		color: var(--ink-4);
		font-size: 11px;
		line-height: 1.55;
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
	.admin-table th { color: var(--ink-4); font: 600 10px/1 var(--font-mono); letter-spacing: .01em; }
	.admin-table td { color: var(--ink-3); font-size: 12px; }
	.admin-table td strong { color: var(--ink-2); font-family: var(--font-ui); }
	.admin-table details { max-width: 360px; }
	.admin-table summary { cursor: pointer; color: var(--ink-2); font-weight: 700; }
	.admin-table details code, .admin-table details small { display: block; margin-top: 5px; overflow-wrap: anywhere; color: var(--ink-4); }
	.error-table { min-width: 760px; }
	.error-table td { vertical-align: top; }
	.error-table td > code { overflow-wrap: anywhere; white-space: normal; }
	.error-time { white-space: nowrap; font-family: var(--font-mono); font-size: 10px !important; }
	.error-status { display: inline-flex; padding: 4px 6px; border-radius: 4px; background: color-mix(in srgb, var(--color-danger) 16%, transparent); color: var(--color-danger); font: 700 10px/1 var(--font-mono); }

	.badge {
		padding: 0.2rem 0.5rem;
		border-radius: 4px;
		background: var(--accent-wash);
		color: var(--accent-ink);
		font: 600 10px/1 var(--font-mono);

	}

	.badge.suspended {
		background: color-mix(in srgb, var(--color-danger) 16%, transparent);
		color: var(--color-danger);
	}

	.btn-sm {
		font: 600 10px/1 var(--font-mono);
		padding: 7px 9px;
		border-radius: var(--radius-control);
		border: 1px solid var(--border-ui);
		background: transparent;
		color: var(--ink-2);

	}
	.btn-sm:hover { border-color: var(--accent-ink); color: var(--accent-ink); }
	.btn-sm.danger { border-color: var(--color-danger-border); color: var(--color-danger); }
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
		border-radius: var(--radius-control);
		font: 700 10px/1 var(--font-mono);

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
	.loading { color: var(--ink-4); font: 600 10px/1 var(--font-mono); }
	@media (max-width: 560px) { .admin-page { padding: 16px; }.metrics-grid { grid-template-columns: repeat(2, minmax(0,1fr)); }.card { padding: 14px; } }
</style>
