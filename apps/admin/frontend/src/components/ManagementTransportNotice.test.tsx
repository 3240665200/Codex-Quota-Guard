import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import ManagementTransportNotice from './ManagementTransportNotice';

describe('management transport notice', () => {
  it('warns about plaintext HTTP without rendering a blocking confirmation', () => {
    render(<ManagementTransportNotice supported protocol="http:" />);
    expect(screen.getByText(/HTTP.*明文/)).toBeInTheDocument();
    expect(screen.getByText(/窃听或篡改/)).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('does not show an HTTP warning for HTTPS', () => {
    render(<ManagementTransportNotice supported protocol="https:" />);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it.each(['file:', 'ftp:', 'data:', ''])('explains unsupported protocol %s', (protocol) => {
    render(<ManagementTransportNotice supported={false} protocol={protocol} />);
    expect(screen.getByText(/HTTP 或 HTTPS/)).toBeInTheDocument();
    expect(screen.queryByText(/HTTP.*明文/)).not.toBeInTheDocument();
  });
});
