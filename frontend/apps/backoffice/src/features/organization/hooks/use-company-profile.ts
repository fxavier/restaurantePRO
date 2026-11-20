import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { CompanyProfileUpdateRequest, CompanyProfileView } from '../store-types';

const COMPANY_PROFILE_KEY = ['backoffice', 'company-profile'] as const;

/** The company's legal profile (COMPANY_MANAGE, a step-up permission: the read may answer 401 MFA_STEP_UP_REQUIRED). */
export function useCompanyProfile(enabled: boolean) {
  const api = useApi();
  return useQuery({
    queryKey: COMPANY_PROFILE_KEY,
    queryFn: async () => (await api.GET('/api/v1/backoffice/company-profile')).data as CompanyProfileView,
    enabled,
  });
}

/** No Idempotency-Key (the contract takes none); the profile is read again whatever the answer. */
export function useUpdateCompanyProfile() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CompanyProfileUpdateRequest) =>
      api.PUT('/api/v1/backoffice/company-profile', { body }).then(({ data }) => data as CompanyProfileView),
    onSettled: () => queryClient.invalidateQueries({ queryKey: COMPANY_PROFILE_KEY }),
  });
}

/** The profile as the server has it now, for an edit that met a newer version. */
export function useReadCompanyProfile() {
  const api = useApi();
  return async () => (await api.GET('/api/v1/backoffice/company-profile')).data as CompanyProfileView;
}
