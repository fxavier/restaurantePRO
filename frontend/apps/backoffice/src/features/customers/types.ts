import type { components } from '@restaurantpro/api-client';

type Schemas = components['schemas'];
export type CustomerCandidate = Schemas['com.xavier.backend.customers.model.dto.CustomerCandidate'];
export type CustomerDetails = Schemas['com.xavier.backend.customers.model.dto.CustomerDetails'];
export type CustomerCreateRequest = Schemas['com.xavier.backend.customers.model.dto.CustomerCreateRequest'];
export type CustomerUpdateRequest = Schemas['com.xavier.backend.customers.model.dto.CustomerUpdateRequest'];
export type AddressView = Schemas['com.xavier.backend.customers.model.dto.AddressView'];
export type AddressRequest = Schemas['com.xavier.backend.customers.model.dto.AddressRequest'];
export type AddressUpdateRequest = Schemas['com.xavier.backend.customers.model.dto.AddressUpdateRequest'];
export type PhoneView = Schemas['com.xavier.backend.customers.model.dto.PhoneView'];
export type PhoneRequest = Schemas['com.xavier.backend.customers.model.dto.PhoneRequest'];
export type CreditView = Schemas['com.xavier.backend.customers.CreditView'];
export type DebtRow = Schemas['com.xavier.backend.customers.model.dto.DebtRow'];
export type StatementView = Schemas['com.xavier.backend.customers.model.dto.StatementView'];
export type StatementLine = Schemas['com.xavier.backend.customers.model.dto.StatementLine'];
export type LoyaltyBalance = Schemas['com.xavier.backend.promotions.LoyaltyBalance'];
export type LoyaltyStatement = Schemas['com.xavier.backend.promotions.loyalty.model.dto.StatementView'];
export type LoyaltyAdjustmentRequest = Schemas['com.xavier.backend.promotions.loyalty.model.dto.AdjustmentRequest'];
